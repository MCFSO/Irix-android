package org.mcfso.irix.gnss.sink

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Synchronous JSONL storage for the controller's IO worker. Each successful append
 * syncs its file descriptor. Retains eight 8 MiB segments; exports are never pruned.
 * Errors are sticky. An IO failure stops writes to avoid appending after a partial record.
 */
class GnssLogStore(private val directory: File) {
    private val lock = Any()
    private val session = UUID.randomUUID().toString()
    private val segments = mutableListOf<File>()
    private var sequence = 0L
    private var output: FileOutputStream? = null
    private var size = 0L
    private var closed = false
    private var failed = false

    @Volatile var currentFile: File? = null
        private set
    @Volatile var lines: Long = 0
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var rejectedLines: Long = 0
        private set

    fun append(line: String) = synchronized(lock) {
        if (closed || failed) {
            rejectedLines++
            if (error == null) error = "Log store is closed"
            return@synchronized
        }
        val record = try {
            gnssJsonRecord(line)
        } catch (e: Exception) {
            rejectedLines++
            report("Rejected log record", e)
            return@synchronized
        }
        try {
            if (output == null || size + record.size > SEGMENT_BYTES) openSegment()
            val stream = checkNotNull(output)
            stream.write(record)
            stream.flush()
            stream.fd.sync()
            size += record.size
            lines++
        } catch (e: Exception) {
            rejectedLines++
            failed = true
            report("Cannot append GNSS log", e)
            finishSegment()
        }
    }

    /** Copies retained segments of this session under lock, including after close. */
    fun snapshot(destination: File): File? = synchronized(lock) {
        if (segments.isEmpty()) return@synchronized null
        var created: File? = null
        try {
            output?.let { it.flush(); it.fd.sync() }
            val target = destination.canonicalFile
            require(!SEGMENT_NAME.matches(target.name)) { "Export uses a reserved log filename" }
            val parent = target.parentFile ?: throw IOException("Export has no parent directory")
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create export directory")
            if (!target.createNewFile()) throw IOException("Export destination already exists")
            created = target
            FileOutputStream(target).use { copy ->
                synchronized(retentionLock) {
                    segments.filter { it.exists() }.forEach { source ->
                        source.inputStream().use { it.copyTo(copy) }
                    }
                }
                copy.flush()
                copy.fd.sync()
            }
            target
        } catch (e: Exception) {
            report("Cannot snapshot GNSS log", e)
            created?.let {
                if (!it.delete()) error = "${error}; cannot remove incomplete export: ${it.path}"
            }
            null
        }
    }

    fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            finishSegment()
        }
    }

    private fun openSegment() {
        finishSegment()
        if (failed) throw IOException(error ?: "Cannot finish GNSS log segment")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create log directory")
        synchronized(retentionLock) {
            val file = File(directory, "gnss-$session-${sequence.toString().padStart(8, '0')}.jsonl")
            sequence++
            if (!file.createNewFile()) throw IOException("GNSS segment already exists")
            try {
                output = FileOutputStream(file, true)
            } catch (e: Exception) {
                if (!file.delete()) report("Cannot remove unopened log segment", e)
                throw e
            }
            currentFile = file
            segments.add(file)
            activeFiles.add(file.absolutePath)
            size = 0
            val owned = directory.listFiles { candidate -> SEGMENT_NAME.matches(candidate.name) }
                ?: throw IOException("Cannot list log directory")
            var remaining = owned.size
            owned.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name }).forEach { old ->
                if (remaining > MAX_SEGMENTS && old.absolutePath !in activeFiles) {
                    if (!old.delete()) throw IOException("Cannot remove old log: ${old.name}")
                    remaining--
                }
            }
        }
    }

    private fun finishSegment() {
        val stream = output ?: return
        output = null
        try {
            stream.flush()
            stream.fd.sync()
        } catch (e: Exception) {
            failed = true
            report("Cannot flush GNSS log", e)
        } finally {
            try {
                stream.close()
            } catch (e: Exception) {
                failed = true
                report("Cannot close GNSS log", e)
            } finally {
                synchronized(retentionLock) { currentFile?.let { activeFiles.remove(it.absolutePath) } }
            }
        }
    }

    private fun report(action: String, cause: Exception) {
        error = "$action: ${cause.message ?: cause.javaClass.simpleName}"
    }

    private companion object {
        const val SEGMENT_BYTES = 8L * 1024 * 1024
        const val MAX_SEGMENTS = 8
        val SEGMENT_NAME = Regex("gnss-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[0-9]{8,}\\.jsonl")
        val retentionLock = Any()
        val activeFiles = mutableSetOf<String>()
    }
}

/** Shared framing boundary: reject invalid records whole, never truncate or repair them. */
internal fun gnssJsonRecord(line: String): ByteArray {
    require(line.length <= MAX_RECORD_BYTES) { "JSON record exceeds 1 MiB" }
    require('\n' !in line && '\r' !in line) { "JSON record must occupy one line" }
    var depth = 0
    var quoted = false
    var escaped = false
    line.forEach { char ->
        if (quoted) {
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == '"') quoted = false
        } else when (char) {
            '"' -> quoted = true
            '{', '[' -> { depth++; require(depth <= 64) { "JSON record nesting exceeds 64" } }
            '}', ']' -> depth--
        }
    }
    val element = Json.parseToJsonElement(line)
    require(element is JsonObject) { "GNSS record must be a JSON object" }
    validateJsonPrimitives(element)
    val encoded = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(line))
    require(encoded.remaining() + 1 <= MAX_RECORD_BYTES) { "JSON record exceeds 1 MiB" }
    return ByteArray(encoded.remaining() + 1).also { bytes ->
        encoded.get(bytes, 0, bytes.size - 1)
        bytes[bytes.lastIndex] = '\n'.code.toByte()
    }
}

private fun validateJsonPrimitives(element: JsonElement) {
    when (element) {
        is JsonObject -> element.values.forEach(::validateJsonPrimitives)
        is JsonArray -> element.forEach(::validateJsonPrimitives)
        is JsonPrimitive -> if (!element.isString && element !== JsonNull) {
            require(element.content == "true" || element.content == "false" || JSON_NUMBER.matches(element.content)) {
                "Invalid JSON primitive"
            }
        }
    }
}

private const val MAX_RECORD_BYTES = 1024 * 1024
private val JSON_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
