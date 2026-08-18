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
import org.mcfso.irix.data.model.InstanceConfig

@Composable
fun CreateInstanceDialog(onDismiss: () -> Unit, onCreate: (InstanceConfig) -> Unit) {
    var nickname by remember { mutableStateOf("") }
    var startCommand by remember { mutableStateOf("") }
    var stopCommand by remember { mutableStateOf("stop") }
    var cwd by remember { mutableStateOf("") }

    val valid = nickname.isNotBlank() && startCommand.isNotBlank() && cwd.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_instance)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(nickname, { nickname = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(startCommand, { startCommand = it }, label = { Text(stringResource(R.string.start_command)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(stopCommand, { stopCommand = it }, label = { Text(stringResource(R.string.stop_command)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(cwd, { cwd = it }, label = { Text(stringResource(R.string.workdir)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCreate(InstanceConfig(
                    nickname = nickname.trim(),
                    startCommand = startCommand.trim(),
                    stopCommand = stopCommand.trim(),
                    cwd = cwd.trim(),
                ))
            }, enabled = valid) { Text(stringResource(R.string.create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
