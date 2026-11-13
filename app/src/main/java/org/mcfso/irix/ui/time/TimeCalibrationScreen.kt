package org.mcfso.irix.ui.time

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mcfso.irix.AppContainer
import org.mcfso.irix.R
import org.mcfso.irix.gnss.GnssCalibrationService
import org.mcfso.irix.gnss.GnssConfig
import org.mcfso.irix.gnss.GnssUiState
import org.mcfso.irix.gnss.GnssMode
import org.mcfso.irix.gnss.model.GnssSource
import org.mcfso.irix.gnss.model.GnssTimeSample
import org.mcfso.irix.gnss.model.GnssVerdict
import org.mcfso.irix.ui.common.InfoRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * GNSS 时间校准控制台。
 *
 * 这里展示的一切都是**本机诊断**：本应用不修改系统时间，也不会代替 Rust `xmc_time`
 * 做融合裁决。页面只负责采集、观察质量、落盘与导出。
 */
@Composable
fun TimeCalibrationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = AppContainer.gnss
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var nowMonoNs by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(state.running) {
        while (true) {
            nowMonoNs = SystemClock.elapsedRealtimeNanos()
            kotlinx.coroutines.delay(1_000L)
        }
    }

    // 回到前台时刷新能力（用户可能刚在系统设置里授权或打开 GPS）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) controller.refreshCapabilities()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Compose 要求资源在组合内读取：先取出字符串，供非 @Composable 回调使用
    val rationaleText = stringResource(R.string.gnss_permission_rationale)
    val exportFailedText = stringResource(R.string.gnss_export_failed)
    val exportTitleText = stringResource(R.string.gnss_export_title)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        controller.refreshCapabilities()
        if (controller.state.value.capabilities.fineLocationGranted) {
            GnssCalibrationService.start(context)
        } else {
            controller.reportError(rationaleText)
        }
    }

    fun requestStart() {
        val caps = state.capabilities
        if (!caps.fineLocationGranted) {
            val wanted = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            // 通知权限只影响前台服务通知的可见性，拒绝不阻塞采集
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                wanted += Manifest.permission.POST_NOTIFICATIONS
            }
            permissionLauncher.launch(wanted.toTypedArray())
            return
        }
        if (!caps.gpsAvailable || !caps.gpsEnabled) {
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        GnssCalibrationService.start(context)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(
                stringResource(R.string.gnss_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CapabilityCard(state)

            if (!state.capabilities.fineLocationGranted) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.gnss_permission_rationale),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null),
                                    ),
                                )
                            },
                        ) { Text(stringResource(R.string.gnss_open_settings)) }
                    }
                }
            }

            ControlCard(state, onConfigChange = controller::updateConfig)

            Button(
                onClick = { if (state.running) GnssCalibrationService.stop(context) else requestStart() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (state.running) R.string.gnss_stop else R.string.gnss_start))
            }

            LiveCard(state, nowMonoNs)

            StatsCard(state)

            if (state.logLines > 0) {
                TextButton(
                    onClick = {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) { controller.exportLog() }
                            if (file == null) {
                                controller.reportError(exportFailedText)
                                return@launch
                            }
                            try {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.gnss.logs",
                                    file,
                                )
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(send, exportTitleText),
                                )
                            } catch (e: Exception) {
                                controller.reportError(e.message ?: e.javaClass.simpleName)
                            }
                        }
                    },
                ) { Text(stringResource(R.string.gnss_export)) }
            }

            state.error?.let { message ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.gnss_error), fontWeight = FontWeight.SemiBold)
                        Text(message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Text(
                stringResource(R.string.gnss_diagnostic_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun CapabilityCard(state: GnssUiState) {
    val caps = state.capabilities
    SectionCard(stringResource(R.string.gnss_capabilities)) {
        InfoRow(stringResource(R.string.gnss_device), caps.model)
        InfoRow(stringResource(R.string.gnss_sdk), "API ${caps.sdkInt}")
        InfoRow(
            stringResource(R.string.gnss_gps),
            when {
                !caps.gpsAvailable -> stringResource(R.string.gnss_gps_absent)
                caps.gpsEnabled -> stringResource(R.string.gnss_gps_on)
                else -> stringResource(R.string.gnss_gps_off)
            },
        )
        InfoRow(
            stringResource(R.string.gnss_permission),
            if (caps.fineLocationGranted) stringResource(R.string.gnss_granted) else stringResource(R.string.gnss_denied),
        )
        InfoRow(
            stringResource(R.string.gnss_raw),
            when (caps.rawSupported) {
                true -> stringResource(R.string.gnss_raw_yes)
                false -> stringResource(R.string.gnss_raw_no)
                null -> stringResource(R.string.gnss_raw_unknown)
            },
        )
        InfoRow(
            stringResource(R.string.gnss_clock),
            if (caps.gnssClockSupported) stringResource(R.string.gnss_yes) else stringResource(R.string.gnss_no),
        )
    }
}

@Composable
private fun ControlCard(state: GnssUiState, onConfigChange: (GnssConfig) -> Unit) {
    val config = state.config
    SectionCard(stringResource(R.string.gnss_controls)) {
        Text(stringResource(R.string.gnss_mode), style = MaterialTheme.typography.bodySmall)
        GnssMode.entries.forEach { mode ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = config.mode == mode,
                    onClick = { if (!state.running) onConfigChange(config.copy(mode = mode)) },
                    enabled = !state.running,
                )
                Text(modeLabel(mode), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.gnss_log), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = config.logEnabled,
                onCheckedChange = { if (!state.running) onConfigChange(config.copy(logEnabled = it)) },
                enabled = !state.running,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.gnss_socket), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = config.socketEnabled,
                onCheckedChange = { if (!state.running) onConfigChange(config.copy(socketEnabled = it)) },
                enabled = !state.running,
            )
        }
        Text(
            stringResource(R.string.gnss_socket_port, config.socketPort),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LiveCard(state: GnssUiState, nowMonoNs: Long) {
    val sample = state.latest
    SectionCard(stringResource(R.string.gnss_live)) {
        if (sample == null) {
            Text(stringResource(R.string.gnss_no_sample), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        val ageMs = ((nowMonoNs - sample.monoNs) / 1_000_000L).coerceAtLeast(0L)
        InfoRow(stringResource(R.string.gnss_source), sample.source)
        InfoRow(stringResource(R.string.gnss_utc), formatUtcNs(sample.utcNs))
        InfoRow(stringResource(R.string.gnss_uncertainty), formatSigmaNs(sample.uncertaintyNs))
        InfoRow(
            stringResource(R.string.gnss_uncertainty_source),
            uncertaintySourceLabel(sample.uncertaintySource),
        )
        InfoRow(stringResource(R.string.gnss_satellites), "${sample.svCount}")
        InfoRow(stringResource(R.string.gnss_fix), sample.fix)
        InfoRow(stringResource(R.string.gnss_age), "${ageMs} ms")
        if (ageMs > 2_000L) {
            Text(
                stringResource(R.string.gnss_stale),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(stringResource(R.string.gnss_verdict), style = MaterialTheme.typography.bodySmall)
        Text(
            verdictText(state),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun StatsCard(state: GnssUiState) {
    val stats = state.stats
    SectionCard(stringResource(R.string.gnss_stats)) {
        InfoRow(stringResource(R.string.gnss_accepted), "${stats.accepted}")
        InfoRow(stringResource(R.string.gnss_monitoring), "${stats.monitoring}")
        InfoRow(stringResource(R.string.gnss_rejected), "${stats.rejected}")
        InfoRow(stringResource(R.string.gnss_offset_mean), formatOffsetNs(stats.meanOffsetNs))
        InfoRow(stringResource(R.string.gnss_offset_sigma), stats.sigmaNs?.let { formatSigmaNs(it.toLong()) } ?: "—")
        InfoRow(stringResource(R.string.gnss_validity), String.format(Locale.US, "%.1f%%", stats.validity * 100))
        InfoRow(stringResource(R.string.gnss_log_lines), "${state.logLines}")
        InfoRow(stringResource(R.string.gnss_socket_clients), "${state.socketClients}")
    }
}

@Composable
private fun verdictText(state: GnssUiState): String = when (val verdict = state.verdict) {
    is GnssVerdict.Accepted -> stringResource(
        R.string.gnss_verdict_accepted,
        verdict.weight,
        verdict.consistentFrames,
    )
    is GnssVerdict.Monitoring -> stringResource(R.string.gnss_verdict_monitoring, verdict.reason)
    is GnssVerdict.Rejected -> stringResource(R.string.gnss_verdict_rejected, verdict.reason)
    null -> stringResource(R.string.gnss_no_sample)
}

@Composable
private fun modeLabel(mode: GnssMode): String = when (mode) {
    GnssMode.AUTO -> stringResource(R.string.gnss_mode_auto)
    GnssMode.RAW -> stringResource(R.string.gnss_mode_raw)
    GnssMode.MILLIS -> stringResource(R.string.gnss_mode_millis)
    GnssMode.LOCATION -> stringResource(R.string.gnss_mode_location)
}

private val UTC_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }

private fun formatUtcNs(utcNs: Long): String = UTC_FORMAT.format(Date(utcNs / 1_000_000L))

private fun formatSigmaNs(ns: Long): String =
    if (ns < 1_000_000L) String.format(Locale.US, "%.1f µs", ns / 1_000.0)
    else String.format(Locale.US, "%.3f ms", ns / 1_000_000.0)

private fun uncertaintySourceLabel(source: String?): String = when (source) {
    "hardware" -> "硬件 σ"
    "empirical" -> "实测抖动（MAD）"
    "prior" -> "启动先验"
    "monitoring" -> "仅监控"
    null, "" -> "—"
    else -> source
}

private fun formatOffsetNs(ns: Double?): String =
    ns?.let { String.format(Locale.US, "%+.3f ms", it / 1_000_000.0) } ?: "—"

/** 供最近样本列表复用（保留给后续扩展，避免未使用告警时被误删）。 */
@Suppress("unused")
private fun sampleSourceLabel(sample: GnssTimeSample): String =
    GnssSource.fromWire(sample.source)?.wire ?: sample.source
