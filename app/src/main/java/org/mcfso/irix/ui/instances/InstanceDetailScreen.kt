package org.mcfso.irix.ui.instances

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.mcfso.irix.R
import org.mcfso.irix.data.api.ApiResult
import org.mcfso.irix.data.model.InstanceConfig
import org.mcfso.irix.data.model.InstanceDetail
import org.mcfso.irix.data.model.InstanceStatus
import org.mcfso.irix.data.model.JavaRuntime
import org.mcfso.irix.data.model.NodeConfig
import org.mcfso.irix.data.model.TaskProgress
import org.mcfso.irix.ui.common.ErrorBox
import org.mcfso.irix.ui.common.InfoRow
import org.mcfso.irix.ui.common.LoadingBox
import org.mcfso.irix.ui.common.formatBytes
import org.mcfso.irix.ui.common.formatRate
import org.mcfso.irix.ui.common.instanceStatusLabel
import org.mcfso.irix.ui.common.rememberMessageSnackbarHostState
import org.mcfso.irix.ui.common.statusColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstanceDetailScreen(
    uuid: String,
    node: NodeConfig,
    vm: InstanceDetailViewModel,
    onBack: () -> Unit,
    onOpenFiles: (String) -> Unit,
) {
    var tab by remember { mutableStateOf(0) }

    LaunchedEffect(uuid, node.id) { vm.load(node, uuid) }
    DisposableEffect(Unit) {
        onDispose { vm.stopPolling(); vm.stopLog() }
    }

    val detail by vm.detail.collectAsState()
    val log by vm.log.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val snackbarHostState = rememberMessageSnackbarHostState(message) { vm.consumeMessage() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    val d = (detail as? ApiResult.Success)?.data
                    Text(d?.config?.nickname?.ifEmpty { stringResource(R.string.instance_detail_title) } ?: stringResource(R.string.instance_detail_title))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { vm.load(node, uuid, poll = false) }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val r = detail) {
                null -> LoadingBox()
                is ApiResult.Error -> ErrorBox(r.message) { vm.load(node, uuid, poll = false) }
                is ApiResult.Success -> {
                    ControlBar(
                        status = r.data.status,
                        busy = busy,
                        onStart = { vm.control(node, uuid, InstanceAction.START) },
                        onStop = { vm.control(node, uuid, InstanceAction.STOP) },
                        onRestart = { vm.control(node, uuid, InstanceAction.RESTART) },
                        onKill = { vm.control(node, uuid, InstanceAction.KILL) },
                        onFiles = { onOpenFiles(uuid) },
                    )
                    PrimaryTabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_console)) })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_config)) })
                        Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.tab_info)) })
                        Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text(stringResource(R.string.tab_tools)) })
                    }
                    when (tab) {
                        0 -> ConsoleTab(uuid, node, vm, log, r.data.status)
                        1 -> ConfigTab(r.data.config, onSave = { vm.update(node, uuid, it) })
                        2 -> InfoTab(r.data, vm)
                        3 -> ToolsTab(uuid, node, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlBar(
    status: Int,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onKill: () -> Unit,
    onFiles: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            instanceStatusLabel(status),
            style = MaterialTheme.typography.labelLarge,
            color = statusColor(status),
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onFiles) { Text(stringResource(R.string.files)) }
        Button(onClick = onStart, enabled = !busy && status == InstanceStatus.STOPPED) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.height(16.dp))
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.start))
        }
        FilledTonalButton(onClick = onStop, enabled = !busy && InstanceStatus.isRunningLike(status)) {
            Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.height(16.dp))
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.stop))
        }
        OutlinedButton(onClick = onRestart, enabled = !busy) { Text(stringResource(R.string.restart)) }
        OutlinedButton(onClick = onKill, enabled = !busy && status != InstanceStatus.STOPPED) { Text(stringResource(R.string.kill)) }
    }
}

@Composable
private fun ConsoleTab(
    uuid: String,
    node: NodeConfig,
    vm: InstanceDetailViewModel,
    log: String,
    status: Int,
) {
    var cmd by remember { mutableStateOf("") }
    var autoRefresh by remember { mutableStateOf(true) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val logListState = rememberScrollState()

    // 进入即加载持久化历史日志（服务端保留 ANSI，跨重启不丢）
    LaunchedEffect(uuid, node.id) { vm.loadLog(node, uuid) }
    LaunchedEffect(autoRefresh, uuid) {
        if (autoRefresh) vm.loadLog(node, uuid, auto = true)
        else vm.stopLog()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tab_console), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.loadLog(node, uuid) }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.fetch_log))
                }
                IconButton(onClick = { showClearConfirm = true }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.clear_log))
                }
                androidx.compose.material3.Switch(
                    checked = autoRefresh,
                    onCheckedChange = { autoRefresh = it },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(logListState),
        ) {
            Text(
                text = log.ifEmpty { stringResource(R.string.no_log_output) },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        // 新日志到达时自动滚到底部
        LaunchedEffect(log) {
            if (autoRefresh) logListState.animateScrollTo(logListState.maxValue)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = cmd,
                onValueChange = { cmd = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.command_placeholder)) },
                enabled = InstanceStatus.isRunningLike(status),
                singleLine = true,
            )
            Spacer(Modifier.height(4.dp))
            IconButton(
                onClick = { vm.command(node, uuid, cmd); cmd = "" },
                enabled = InstanceStatus.isRunningLike(status) && cmd.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = stringResource(R.string.send))
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.clear_log)) },
            text = { Text(stringResource(R.string.clear_log_confirm)) },
            confirmButton = {
                TextButton(onClick = { vm.clearLog(node, uuid); showClearConfirm = false }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ConfigTab(config: InstanceConfig, onSave: (InstanceConfig) -> Unit) {
    var nickname by remember(config.nickname) { mutableStateOf(config.nickname) }
    var startCommand by remember(config.startCommand) { mutableStateOf(config.startCommand) }
    var stopCommand by remember(config.stopCommand) { mutableStateOf(config.stopCommand) }
    var cwd by remember(config.cwd) { mutableStateOf(config.cwd) }
    var ie by remember(config.ie) { mutableStateOf(config.ie) }
    var oe by remember(config.oe) { mutableStateOf(config.oe) }
    var autoStart by remember(config.eventTask.autoStart) { mutableStateOf(config.eventTask.autoStart) }
    var autoRestart by remember(config.eventTask.autoRestart) { mutableStateOf(config.eventTask.autoRestart) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(nickname, { nickname = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(startCommand, { startCommand = it }, label = { Text(stringResource(R.string.start_command)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(stopCommand, { stopCommand = it }, label = { Text(stringResource(R.string.stop_command)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(cwd, { cwd = it }, label = { Text(stringResource(R.string.workdir)) }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(ie, { ie = it }, label = { Text(stringResource(R.string.input_encoding)) }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(oe, { oe = it }, label = { Text(stringResource(R.string.output_encoding)) }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            androidx.compose.material3.Checkbox(checked = autoStart, onCheckedChange = { autoStart = it })
            Text(stringResource(R.string.auto_start))
            androidx.compose.material3.Checkbox(checked = autoRestart, onCheckedChange = { autoRestart = it })
            Text(stringResource(R.string.auto_restart))
        }
        Button(onClick = {
            onSave(config.copy(
                nickname = nickname,
                startCommand = startCommand,
                stopCommand = stopCommand,
                cwd = cwd,
                ie = ie,
                oe = oe,
                eventTask = config.eventTask.copy(autoStart = autoStart, autoRestart = autoRestart),
            ))
        }) { Text(stringResource(R.string.save_config)) }
    }
}

@Composable
private fun InfoTab(detail: InstanceDetail, vm: InstanceDetailViewModel) {
    val stats by vm.stats.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.basic_info), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                InfoRow(stringResource(R.string.info_uuid), detail.instanceUuid)
                InfoRow(stringResource(R.string.info_started), detail.started.toString())
                InfoRow(stringResource(R.string.info_status), instanceStatusLabel(detail.status))
                InfoRow(stringResource(R.string.info_disk_usage), formatBytes(detail.space))
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.process_info), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                InfoRow(stringResource(R.string.info_pid), detail.processInfo.pid.toString())
                InfoRow(stringResource(R.string.info_memory), formatBytes(detail.processInfo.memory))
                InfoRow(stringResource(R.string.info_cpu), "%.1f%%".format(detail.processInfo.cpu * 100))
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.live_stats), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val s = stats
                if (s == null || s.pid == 0) {
                    Text(stringResource(R.string.stats_not_running), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    InfoRow(stringResource(R.string.info_pid), s.pid.toString())
                    InfoRow(stringResource(R.string.info_cpu), "%.1f%%".format(s.cpuPercent))
                    InfoRow(stringResource(R.string.info_memory), formatBytes(s.memoryMb * 1024 * 1024))
                    InfoRow(stringResource(R.string.info_download), formatRate(s.networkDownloadBps.toDouble()))
                    InfoRow(stringResource(R.string.info_upload), formatRate(s.networkUploadBps.toDouble()))
                    InfoRow(stringResource(R.string.info_uptime), formatUptimeShort(s.uptimeSec))
                    InfoRow(
                        stringResource(R.string.stats_players),
                        if (s.players != null) "${s.players} / ${s.maxPlayers ?: "—"}" else "—",
                    )
                    InfoRow(stringResource(R.string.stats_tps), if (s.tps != null) "%.1f".format(s.tps) else "—")
                }
            }
        }
    }
}

/** 实例进程运行时长（秒级，简短格式）。 */
private fun formatUptimeShort(seconds: Long): String {
    if (seconds <= 0) return "—"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "%d:%02d:%02d".format(h, m, s)
        m > 0 -> "%d:%02d".format(m, s)
        else -> "${s}s"
    }
}

@Composable
private fun ToolsTab(uuid: String, node: NodeConfig, vm: InstanceDetailViewModel) {
    val java by vm.java.collectAsState()
    val javaJob by vm.javaJob.collectAsState()
    val coreJob by vm.coreJob.collectAsState()

    LaunchedEffect(node.id) { vm.loadJava(node) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ----- Java 运行时 / JDK 安装 -----
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.java_runtime), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                when (val jr = java) {
                    null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodySmall)
                    }
                    is ApiResult.Error -> Text(jr.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    is ApiResult.Success -> {
                        jr.data.all.forEach { JavaRow(it, node, vm) }
                        InstallJavaSection(node, vm, javaJob)
                    }
                }
            }
        }

        // ----- 核心下载 -----
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.core_download), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.core_download_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                var url by remember { mutableStateOf("") }
                var fileName by remember { mutableStateOf("server.jar") }
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.core_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fileName, { fileName = it }, label = { Text(stringResource(R.string.core_file_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = { vm.downloadCore(node, uuid, url.trim(), fileName.trim(), "") },
                    enabled = url.isNotBlank() && fileName.isNotBlank() && coreJob?.status != "running",
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.height(16.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.core_download_start))
                }
                coreJob?.let { TaskProgressView(it) }
            }
        }
    }
}

@Composable
private fun JavaRow(rt: JavaRuntime, node: NodeConfig, vm: InstanceDetailViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                buildString {
                    append(rt.version.ifEmpty { "Java ${rt.major}" })
                    if (rt.available) append(stringResource(R.string.java_available_suffix))
                    else append(stringResource(R.string.java_unavailable_suffix))
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(rt.vendor, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(rt.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (rt.major in 8..30) {
            IconButton(onClick = { vm.uninstallJava(node, rt.major) }) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.jdk_uninstall))
            }
        }
    }
}

@Composable
private fun InstallJavaSection(node: NodeConfig, vm: InstanceDetailViewModel, job: TaskProgress?) {
    var major by remember { mutableStateOf("21") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            major,
            { major = it.filter { c -> c.isDigit() }.take(2) },
            label = { Text(stringResource(R.string.jdk_major)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { major.toIntOrNull()?.let { vm.installJava(node, it) } },
            enabled = major.toIntOrNull() in 8..30 && job?.status != "running",
        ) { Text(stringResource(R.string.jdk_install)) }
    }
    job?.let { TaskProgressView(it) }
}

/** 任务进度条：状态 + 百分比（未知进度时用不确定进度条）+ 消息。 */
@Composable
private fun TaskProgressView(job: TaskProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val label = when (job.status) {
            "running" -> stringResource(R.string.task_running)
            "done" -> stringResource(R.string.task_done)
            "failed" -> stringResource(R.string.task_failed)
            else -> job.status
        }
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        if (job.status == "running") {
            if (job.percent >= 0) {
                LinearProgressIndicator(progress = { job.percent.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("%.0f%%".format(job.percent * 100), style = MaterialTheme.typography.labelSmall)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (job.message.isNotEmpty()) {
            Text(job.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
