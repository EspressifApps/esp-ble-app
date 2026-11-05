package com.espressif.espbleapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun TransferMetricsCard(
    title: String,
    elapsedTimeMs: Long,
    averageSpeedBytesPerSecond: Double,
    dataSizeBytes: Long,
    progress: Float,
    status: String,
    statusColor: Color,
    isComplete: Boolean = false,
    detail: String? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                )
                Surface(
                    shape = CircleShape,
                    color = statusColor.copy(alpha = 0.2f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isComplete) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MetricValue(
                    modifier = Modifier.weight(1f),
                    icon = { tint ->
                        Icon(Icons.Default.Timer, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                    },
                    label = "Time",
                    value = String.format(Locale.getDefault(), "%.2f s", elapsedTimeMs.coerceAtLeast(0L) / 1000.0),
                )
                Spacer(Modifier.width(8.dp))
                MetricValue(
                    modifier = Modifier.weight(1f),
                    icon = { tint ->
                        Icon(Icons.Default.Speed, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                    },
                    label = "Avg Speed",
                    value = String.format(Locale.getDefault(), "%.1f KB/s", averageSpeedBytesPerSecond.coerceAtLeast(0.0) / 1024.0),
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Data Size: $dataSizeBytes Bytes",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MetricValue(
    modifier: Modifier,
    icon: @Composable (Color) -> Unit,
    label: String,
    value: String,
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val tint = MaterialTheme.colorScheme.primary
            icon(tint)
            Spacer(Modifier.width(4.dp))
            Text(
                text = "$label: $value",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = tint,
                ),
            )
        }
    }
}
