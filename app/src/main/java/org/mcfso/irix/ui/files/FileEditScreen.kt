package org.mcfso.irix.ui.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import org.mcfso.irix.R
import org.mcfso.irix.data.model.NodeConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileEditScreen(
    uuid: String,
    path: String,
    node: NodeConfig,
    vm: FilesViewModel,
    onBack: () -> Unit,
) {
    var content by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(path) {
        loading = true
        vm.read(node, uuid, path) { content = it; loading = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(path.substringAfterLast('/')) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { content?.let { vm.write(node, uuid, path, it); onBack() } },
                        enabled = !loading && content != null,
                    ) {
                        Icon(Icons.Outlined.Save, contentDescription = stringResource(R.string.save))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            when {
                loading -> CircularProgressIndicator()
                content == null -> Text(stringResource(R.string.read_file_failed), color = MaterialTheme.colorScheme.error)
                else -> {
                    OutlinedTextField(
                        value = content!!,
                        onValueChange = { content = it },
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}
