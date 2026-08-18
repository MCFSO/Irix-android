package org.mcfso.irix.ui.containers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.mcfso.irix.R
import org.mcfso.irix.data.model.ContainerCreateBody

/** 重启策略选项：值 → 文案资源。 */
private val POLICIES = listOf(
    "no" to R.string.policy_no,
    "always" to R.string.policy_always,
    "on-failure" to R.string.policy_on_failure,
)

/**
 * 新建容器对话框（对应服务端 POST /api/container/create，仅镜像必填）。
 * 端口/卷用逗号分隔输入（host:container），环境变量每行 KEY=VALUE。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerCreateDialog(onDismiss: () -> Unit, onCreate: (ContainerCreateBody) -> Unit) {
    var name by remember { mutableStateOf("") }
    var image by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var workdir by remember { mutableStateOf("") }
    var ports by remember { mutableStateOf("") }
    var volumes by remember { mutableStateOf("") }
    var env by remember { mutableStateOf("") }
    var policy by remember { mutableStateOf("no") }
    var memory by remember { mutableStateOf("") }
    var cpus by remember { mutableStateOf("") }
    var disk by remember { mutableStateOf("") }

    val valid = image.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_container)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    image, { image = it },
                    label = { Text(stringResource(R.string.image)) },
                    placeholder = { Text("nginx:latest") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(command, { command = it }, label = { Text(stringResource(R.string.start_command)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(workdir, { workdir = it }, label = { Text(stringResource(R.string.workdir)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ports, { ports = it }, label = { Text(stringResource(R.string.ports_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(volumes, { volumes = it }, label = { Text(stringResource(R.string.volumes_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(env, { env = it }, label = { Text(stringResource(R.string.env_hint)) }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.restart_policy), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    POLICIES.forEach { (value, labelRes) ->
                        FilterChip(
                            selected = policy == value,
                            onClick = { policy = value },
                            label = { Text(stringResource(labelRes)) },
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        memory, { memory = it },
                        label = { Text(stringResource(R.string.memory_limit)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        disk, { disk = it },
                        label = { Text(stringResource(R.string.disk_limit)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    cpus, { cpus = it },
                    label = { Text(stringResource(R.string.cpu_limit)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCreate(
                    ContainerCreateBody(
                        name = name.trim(),
                        image = image.trim(),
                        command = command.trim().ifEmpty { null },
                        workdir = workdir.trim().ifEmpty { null },
                        ports = ports.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        volumes = volumes.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        env = env.lineSequence().mapNotNull { line ->
                            val kv = line.trim().split('=', limit = 2)
                            if (kv.size == 2 && kv[0].isNotBlank()) kv[0].trim() to kv[1].trim() else null
                        }.toMap(),
                        restartPolicy = policy,
                        memoryLimitMb = memory.trim().toIntOrNull(),
                        cpus = cpus.trim().toDoubleOrNull(),
                        diskLimitMb = disk.trim().toIntOrNull(),
                    ),
                )
            }, enabled = valid) { Text(stringResource(R.string.create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
