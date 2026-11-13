package org.mcfso.irix.gnss.sink

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Opt-in, read-only NDJSON stream. Bind is synchronous; publish never waits on a client. */
class GnssSocketServer(scope: CoroutineScope, private val hello: () -> String) {
    private val lock = Any()
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val ioScope = CoroutineScope(scope.coroutineContext + lifetime + Dispatchers.IO)
    private val clients = mutableSetOf<Client>()
    private val countState = MutableStateFlow(0)
    private val errorState = MutableStateFlow<String?>(null)
    private var listener: ServerSocket? = null
    private var acceptJob: Job? = null
    private var closed = false

    @Volatile var port: Int? = null
        private set
    val clientCount: StateFlow<Int> = countState.asStateFlow()
    val error: StateFlow<String?> = errorState.asStateFlow()

    // Cancellation must close sockets immediately: blocking accept/write do not cooperate
    // with ordinary coroutine cancellation, and parent completion waits for those jobs.
    private val cancellationWatcher = ioScope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            close()
        }
    }

    fun start(port: Int): Boolean = synchronized(lock) {
        if (closed || !lifetime.isActive) {
            errorState.value = "Socket server is closed"
            return@synchronized false
        }
        if (listener != null) {
            if (port == this.port) return@synchronized true
            errorState.value = "Socket server is already listening"
            return@synchronized false
        }
        if (port !in 0..65535) {
            errorState.value = "Invalid socket port: $port"
            return@synchronized false
        }
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), MAX_CLIENTS)
            listener = server
            this.port = server.localPort
            acceptJob = ioScope.launch { acceptClients(server) }
            true
        } catch (e: Exception) {
            report("Cannot bind GNSS socket", e)
            closeSocket(server)
            false
        }
    }

    /** Validates once, then offers the immutable record to each client's bounded queue. */
    fun publish(line: String) {
        val record = try {
            gnssJsonRecord(line)
        } catch (e: Exception) {
            report("Rejected socket record", e)
            return
        }
        synchronized(lock) {
            if (closed) return
            clients.toList().forEach { client ->
                if (!client.queue.trySend(record).isSuccess) {
                    errorState.value = "Disconnected slow GNSS socket client (queue capacity $QUEUE_CAPACITY)"
                    removeClient(client)
                }
            }
        }
    }

    /** Closes descriptors before cancelling jobs, releasing blocked accept and writes. */
    fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        listener?.let(::closeSocket)
        listener = null
        port = null
        clients.toList().forEach(::removeClient)
        acceptJob?.cancel()
        lifetime.cancel()
    }

    private fun acceptClients(server: ServerSocket) {
        try {
            while (lifetime.isActive) {
                val socket = server.accept()
                synchronized(lock) {
                    if (closed || clients.size >= MAX_CLIENTS) {
                        if (!closed) errorState.value = "GNSS socket client limit reached ($MAX_CLIENTS)"
                        closeSocket(socket)
                    } else {
                        try {
                            socket.tcpNoDelay = true
                            val client = Client(socket)
                            clients.add(client)
                            countState.value = clients.size
                            client.job = ioScope.launch { writeClient(client) }
                            client.readJob = ioScope.launch { watchClient(client) }
                        } catch (e: Exception) {
                            report("Cannot configure GNSS client", e)
                            closeSocket(socket)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            synchronized(lock) {
                if (!closed && lifetime.isActive) {
                    report("GNSS socket accept failed", e)
                    close()
                }
            }
        }
    }

    private suspend fun writeClient(client: Client) {
        try {
            val greeting = gnssJsonRecord(hello())
            val output = client.socket.getOutputStream()
            output.write(greeting)
            output.flush()
            for (record in client.queue) {
                output.write(record)
                output.flush()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(lock) {
                if (!closed && client in clients) report("GNSS client disconnected", e)
            }
        } finally {
            synchronized(lock) { removeClient(client) }
        }
    }

    private fun removeClient(client: Client) {
        clients.remove(client)
        client.queue.cancel()
        closeSocket(client.socket)
        client.job?.cancel()
        client.readJob?.cancel()
        countState.value = clients.size
    }

    /**
     * 单向流的接收端可能写回 ack/心跳；必须持续读取，否则对端写满缓冲区会阻塞。
     * 同时读失败/EOF 是判断客户端断开的最快信号。
     */
    private suspend fun watchClient(client: Client) {
        try {
            val input = client.socket.getInputStream()
            val buffer = ByteArray(512)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 连接已断开，走 finally 统一清理
        } finally {
            synchronized(lock) { if (client in clients) removeClient(client) }
        }
    }

    private fun closeSocket(socket: Socket) {
        try {
            socket.close()
        } catch (e: IOException) {
            report("Cannot close GNSS client socket", e)
        }
    }

    private fun closeSocket(socket: ServerSocket) {
        try {
            socket.close()
        } catch (e: IOException) {
            report("Cannot close GNSS listener", e)
        }
    }

    private fun report(action: String, cause: Exception) {
        errorState.value = "$action: ${cause.message ?: cause.javaClass.simpleName}"
    }

    private class Client(val socket: Socket) {
        val queue = Channel<ByteArray>(QUEUE_CAPACITY)
        var job: Job? = null
        var readJob: Job? = null
    }

    private companion object {
        const val MAX_CLIENTS = 4
        const val QUEUE_CAPACITY = 32
    }
}
