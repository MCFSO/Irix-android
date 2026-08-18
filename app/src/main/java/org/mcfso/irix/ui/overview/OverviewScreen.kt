package org.mcfso.irix.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mcfso.irix.R
import org.mcfso.irix.data.api.ApiResult
import org.mcfso.irix.data.model.OverviewResponse
import org.mcfso.irix.ui.common.ErrorBox
import org.mcfso.irix.ui.common.InfoRow
import org.mcfso.irix.ui.common.LoadingBox
import org.mcfso.irix.ui.common.UsageBar
import org.mcfso.irix.ui.common.formatBytes
import org.mcfso.irix.ui.common.formatPercent
import org.mcfso.irix.ui.common.formatRate
import org.mcfso.irix.ui.SessionViewModel

@Composable
fun OverviewScreen(session: SessionViewModel) {
    val selected by session.selected.collectAsState()
    val overview by session.overview.collectAsState()
    val refreshing by session.refreshing.collectAsState()

    LaunchedEffect(selected?.id) {
        session.refreshOverview(force = true)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (selected != null) stringResource(R.string.overview_node_title, selected!!.name)
                else stringResource(R.string.overview_no_node),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = { session.refreshOverview(force = true) }, enabled = !refreshing) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.refresh))
            }
        }

        when (val r = overview) {
            null, is ApiResult.Error -> {
                if (r is ApiResult.Error) ErrorBox(r.message) { session.refreshOverview(force = true) }
                else if (refreshing) LoadingBox()
                else OverviewEmpty()
            }
            is ApiResult.Success -> OverviewContent(r.data)
        }
    }
}

@Composable
private fun OverviewEmpty() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("📡", style = MaterialTheme.typography.displaySmall)
        Text(stringResource(R.string.overview_empty_hint), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun OverviewContent(data: OverviewResponse) {
    val sys = data.system
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 系统信息
        InfoCard(title = stringResource(R.string.info_system)) {
            InfoRow(stringResource(R.string.info_hostname), sys.hostname.ifEmpty { "—" })
            InfoRow(stringResource(R.string.info_os), sys.type.ifEmpty { sys.platform.ifEmpty { "—" } })
            InfoRow(stringResource(R.string.info_version), sys.version.ifEmpty { sys.release.ifEmpty { "—" } })
            InfoRow(stringResource(R.string.info_uptime), formatUptime(sys.uptime))
            InfoRow(stringResource(R.string.info_node_version), data.version.ifEmpty { "—" })
        }

        // CPU
        InfoCard(title = stringResource(R.string.info_cpu)) {
            UsageBar(stringResource(R.string.usage_rate), sys.cpuUsage)
        }

        // 内存
        InfoCard(title = stringResource(R.string.info_mem)) {
            UsageBar(stringResource(R.string.usage_rate), sys.memUsage)
            InfoRow(stringResource(R.string.used_total), "${formatBytes(sys.totalmem - sys.freemem)} / ${formatBytes(sys.totalmem)}")
        }

        // 磁盘
        InfoCard(title = stringResource(R.string.info_disk)) {
            UsageBar(stringResource(R.string.usage_rate), sys.diskusage)
            InfoRow(stringResource(R.string.used_total), "${formatBytes(sys.diskused)} / ${formatBytes(sys.disktotal)}")
        }

        // 网络
        InfoCard(title = stringResource(R.string.info_network)) {
            InfoRow(stringResource(R.string.info_download), formatRate(sys.networkDownload))
            InfoRow(stringResource(R.string.info_upload), formatRate(sys.networkUpload))
        }

        // 实例统计
        InfoCard(title = stringResource(R.string.info_instances)) {
            val r = data.remote.firstOrNull()
            InfoRow(stringResource(R.string.running_total), "${r?.instance?.running ?: 0} / ${r?.instance?.total ?: 0}")
            InfoRow(stringResource(R.string.daemon_available), "${data.remoteCount.available} / ${data.remoteCount.total}")
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** 运行时长 → 本地化文案（天/小时/分钟，0 值部分省略）。 */
@Composable
private fun formatUptime(seconds: Long): String {
    if (seconds <= 0) return "—"
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    return when {
        d > 0 -> stringResource(R.string.uptime_dhm, d, h, m)
        h > 0 -> stringResource(R.string.uptime_hm, h, m)
        else -> stringResource(R.string.uptime_m, m)
    }
}
