package org.mcfso.irix.ui.instances

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import org.mcfso.irix.R

/**
 * 导入目录创建实例（服务端 POST /api/instance/import）。
 * 节点校验目录特征（server.jar / eula.txt 等）后自动创建实例。
 */
@Composable
fun ImportInstanceDialog(onDismiss: () -> Unit, onImport: (path: String, nickname: String) -> Unit) {
    var path by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_instance)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    path,
                    { path = it },
                    label = { Text(stringResource(R.string.import_instance_path)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    nickname,
                    { nickname = it },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(path.trim(), nickname.trim()) },
                enabled = path.isNotBlank(),
            ) { Text(stringResource(R.string.import_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
