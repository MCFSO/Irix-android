package org.mcfso.irix.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mcfso.irix.R
import org.mcfso.irix.data.model.InstanceStatus
import org.mcfso.irix.data.api.ApiResult

/** 字节数格式化（B/KB/MB/GB/TB）。 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val unit = 1024.0
    if (bytes < unit) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(unit)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return "%.1f %sB".format(bytes / Math.pow(unit, exp.toDouble()), pre)
}

/** 速率格式化（字节/秒）。 */
fun formatRate(bytesPerSec: Double): String = formatBytes(bytesPerSec.toLong()) + "/s"

/** 0~1 比例转百分比字符串。 */
fun formatPercent(ratio: Double): String = "%.1f%%".format((ratio.coerceIn(0.0, 1.0)) * 100)

/** 使用率对应颜色：低绿 / 中黄 / 高红。 */
fun usageColor(ratio: Double): Color {
    val r = ratio.coerceIn(0.0, 1.0)
    return when {
        r >= 0.9 -> Color(0xFFE53935)
        r >= 0.7 -> Color(0xFFFB8C00)
        else -> Color(0xFF43A047)
    }
}

/** 实例状态对应的展示色。 */
fun statusColor(status: Int): Color = when (status) {
    InstanceStatus.RUNNING -> Color(0xFF43A047)
    InstanceStatus.STARTING -> Color(0xFF1E88E5)
    InstanceStatus.STOPPING -> Color(0xFFFB8C00)
    InstanceStatus.BUSY -> Color(0xFF8E24AA)
    else -> Color(0xFF9E9E9E)
}

/** 实例状态 → 本地化文案。 */
@Composable
fun instanceStatusLabel(status: Int): String = when (status) {
    InstanceStatus.BUSY -> stringResource(R.string.status_busy)
    InstanceStatus.STOPPED -> stringResource(R.string.status_stopped)
    InstanceStatus.STOPPING -> stringResource(R.string.status_stopping)
    InstanceStatus.STARTING -> stringResource(R.string.status_starting)
    InstanceStatus.RUNNING -> stringResource(R.string.status_running)
    else -> stringResource(R.string.status_unknown, status)
}

/**
 * 消息 Snackbar 宿主状态：message 非空时弹出 Snackbar 并回调 onConsumed 清除消息。
 * 供各页面把 ViewModel 的 message/toast 流接到 SnackbarHost 使用。
 */
@Composable
fun rememberMessageSnackbarHostState(message: String?, onConsumed: () -> Unit): SnackbarHostState {
    val hostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        hostState.showSnackbar(m)
        onConsumed()
    }
    return hostState
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier, label: String? = null) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(label ?: stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ErrorBox(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⚠️", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.load_failed), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onRetry != null) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

@Composable
fun EmptyBox(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 通用行：标签 + 值，用于详情卡片。 */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

/** 把 ApiResult 转成可显示的状态：加载中 / 错误 / 空 / 数据。 */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Error(val message: String) : LoadState<Nothing>
    data object Empty : LoadState<Nothing>
    data class Data<T>(val value: T) : LoadState<T>
}

fun <T> ApiResult<T>.toLoadState(emptyCheck: (T) -> Boolean = { false }): LoadState<T> = when (this) {
    is ApiResult.Success -> if (emptyCheck(data)) LoadState.Empty else LoadState.Data(data)
    is ApiResult.Error -> LoadState.Error(message)
}
