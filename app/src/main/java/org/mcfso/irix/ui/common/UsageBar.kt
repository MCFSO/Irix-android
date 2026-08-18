package org.mcfso.irix.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 横向使用率条：标签 + 比例条 + 百分比。ratio 为 0~1。 */
@Composable
fun UsageBar(
    label: String,
    ratio: Double,
    modifier: Modifier = Modifier,
    color: Color = usageColor(ratio),
    valueText: String = formatPercent(ratio),
) {
    val animated by animateFloatAsState(
        targetValue = ratio.coerceIn(0.0, 1.0).toFloat(),
        animationSpec = tween(durationMillis = 600),
        label = "usage",
    )
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.fillMaxWidth(0.5f))
            Text(
                valueText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
        }
        Spacer(Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp),
        ) {
            val w = size.width
            val h = size.height
            drawRoundRect(
                color = trackColor,
                size = Size(w, h),
                cornerRadius = CornerRadius(h / 2, h / 2),
            )
            drawRoundRect(
                color = color,
                size = Size(w * animated, h),
                cornerRadius = CornerRadius(h / 2, h / 2),
            )
        }
    }
}
