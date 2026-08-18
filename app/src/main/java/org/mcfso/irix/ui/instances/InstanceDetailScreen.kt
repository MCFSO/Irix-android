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
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import org.mcfso.irix.data.model.InstanceStatus
import org.mcfso.irix.data.model.NodeConfig
import org.mcfso.irix.ui.common.ErrorBox
import org.mcfso.irix.ui.common.InfoRow
import org.mcfso.irix.ui.common.LoadingBox
import org.mcfso.irix.ui.common.formatBytes
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
                    }
                    when (tab) {
                        0 -> ConsoleTab(uuid, node, vm, log, r.data.status)
                        1 -> ConfigTab(r.data.config, onSave = { vm.update(node, uuid, it) })
                        2 -> InfoTab(r.data)
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
    var autoRefresh by remember { mutableStateOf(false) }

    LaunchedEffect(autoRefresh, uuid) {
        if (autoRefresh) vm.loadLog(node, uuid, auto = true)
        else vm.stopLog()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = { vm.loadLog(node, uuid) }) { Text(stringResource(R.string.fetch_log)) }
            androidx.compose.material3.Switch(
                checked = autoRefresh,
                onCheckedChange = { autoRefresh = it },
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = log.ifEmpty { stringResource(R.string.no_log_output) },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
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
private fun InfoTab(detail: org.mcfso.irix.data.model.InstanceDetail) {
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
    }
}
