package com.espressif.espbleapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.espressif.espbleapp.R
import com.espressif.espbleapp.flux.FluxConnectionState
import com.espressif.espbleapp.flux.FluxReceivedMessage
import com.espressif.espbleapp.viewmodel.FluxViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FluxAppRoot(
    vm: FluxViewModel = viewModel(),
    onBackToLauncher: (() -> Unit)? = null,
) {
    var tab by remember { mutableIntStateOf(0) }

    val connectionState by vm.connectionState.collectAsStateWithLifecycle()
    val connectedDevice by vm.connectedDevice.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.flux_title),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        Spacer(Modifier.width(10.dp))

                        val (statusText, badgeColor) = when (connectionState) {
                            FluxConnectionState.CONNECTED -> stringResource(R.string.flux_status_connected) to Color(0xFF4CAF50)
                            FluxConnectionState.CONNECTING -> stringResource(R.string.flux_status_connecting) to Color(0xFFFF9800)
                            else -> stringResource(R.string.flux_status_disconnected) to Color.Gray
                        }

                        Surface(
                            shape = CircleShape,
                            color = badgeColor.copy(alpha = 0.2f),
                        ) {
                            Text(
                                text = if (connectionState == FluxConnectionState.CONNECTED && connectedDevice != null) {
                                    connectedDevice?.name.orEmpty()
                                } else {
                                    statusText
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = badgeColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (onBackToLauncher != null) {
                        IconButton(onClick = onBackToLauncher) {
                            Icon(
                                imageVector = Icons.Default.Apps,
                                contentDescription = stringResource(R.string.back_to_launcher),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = null) },
                    label = { Text(stringResource(R.string.flux_nav_scan)) },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                    label = { Text(stringResource(R.string.flux_nav_send)) },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null) },
                    label = { Text(stringResource(R.string.flux_nav_logs)) },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            when (tab) {
                0 -> FluxScanTab(vm = vm)
                1 -> FluxTransferTab(vm = vm)
                2 -> FluxLogsTab(vm = vm)
            }
        }
    }
}

@Composable
private fun FluxScanTab(vm: FluxViewModel) {
    val devices by vm.scannedDevices.collectAsStateWithLifecycle()
    val isScanning by vm.isScanning.collectAsStateWithLifecycle()
    val connectionState by vm.connectionState.collectAsStateWithLifecycle()
    val connectedDevice by vm.connectedDevice.collectAsStateWithLifecycle()

    var filterFluxService by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.flux_filter_service),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = filterFluxService,
                        onCheckedChange = { filterFluxService = it },
                    )
                }

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        if (isScanning) {
                            vm.stopScan()
                        } else {
                            vm.startScan(filterFluxService)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (isScanning) stringResource(R.string.flux_scan_stop) else stringResource(R.string.flux_scan_start),
                    )
                }

                if (isScanning) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (devices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.flux_no_devices),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(devices) { dev ->
                    val isCurrent = connectedDevice?.address == dev.address
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrent) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            },
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    text = dev.name,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                    ),
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "${dev.address}  ·  RSSI ${dev.rssi} dBm",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            if (isCurrent && connectionState == FluxConnectionState.CONNECTED) {
                                OutlinedButton(onClick = { vm.disconnect() }) {
                                    Text(stringResource(R.string.flux_disconnect))
                                }
                            } else {
                                Button(
                                    onClick = { vm.connect(dev) },
                                    enabled = connectionState == FluxConnectionState.DISCONNECTED,
                                ) {
                                    Text(stringResource(R.string.flux_connect))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FluxTransferTab(vm: FluxViewModel) {
    val connectionState by vm.connectionState.collectAsStateWithLifecycle()
    val connectedDevice by vm.connectedDevice.collectAsStateWithLifecycle()
    val mtu by vm.mtu.collectAsStateWithLifecycle()
    val progress by vm.transferProgress.collectAsStateWithLifecycle()
    val receivedData by vm.receivedData.collectAsStateWithLifecycle()

    var customText by remember { mutableStateOf("") }
    var metricsClockNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(progress.startedAtMs, progress.isSending, progress.isWaitingForAck, progress.ackReceived) {
        if (progress.startedAtMs > 0L && !progress.ackReceived && (progress.isSending || progress.isWaitingForAck)) {
            while (true) {
                metricsClockNowMs = System.currentTimeMillis()
                delay(100L)
            }
        } else {
            metricsClockNowMs = System.currentTimeMillis()
        }
    }
    val metricsElapsedMs = if (
        progress.startedAtMs > 0L && !progress.ackReceived && (progress.isSending || progress.isWaitingForAck)
    ) {
        (metricsClockNowMs - progress.startedAtMs).coerceAtLeast(0L)
    } else {
        progress.elapsedTimeMs
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                ) {
                    if (connectionState == FluxConnectionState.CONNECTED && connectedDevice != null) {
                        Text(
                            text = stringResource(
                                R.string.flux_connected_device,
                                connectedDevice!!.name,
                                connectedDevice!!.address,
                            ),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.flux_mtu_label, mtu),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { vm.disconnect() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.flux_disconnect))
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.flux_status_disconnected),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        if (progress.isSending || progress.transferredBytes > 0) {
            item {
                val (statusLabel, statusColor) = when {
                    progress.failureMessage != null -> "Failed" to MaterialTheme.colorScheme.error
                    progress.ackReceived -> stringResource(R.string.flux_ack_status_confirmed) to Color(0xFF4CAF50)
                    progress.isWaitingForAck -> stringResource(R.string.flux_ack_status_waiting) to Color(0xFFFF9800)
                    else -> "传输中…" to MaterialTheme.colorScheme.primary
                }
                TransferMetricsCard(
                    title = stringResource(R.string.flux_performance_title),
                    elapsedTimeMs = metricsElapsedMs,
                    averageSpeedBytesPerSecond = if (metricsElapsedMs > 0L) {
                        progress.confirmedBytes.toDouble() * 1000.0 / metricsElapsedMs
                    } else {
                        0.0
                    },
                    dataSizeBytes = progress.totalBytes.toLong(),
                    progress = progress.progressPercent / 100f,
                    status = statusLabel,
                    statusColor = statusColor,
                    isComplete = progress.ackReceived,
                    detail = buildString {
                        append("Confirmed: ${progress.confirmedBytes.coerceAtMost(progress.totalBytes)} / ${progress.totalBytes} Bytes")
                        progress.failureMessage?.let { append("\n$it") }
                    },
                )
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        text = "Quick Test Payloads",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { vm.sendTestBuffer(482) },
                            enabled = connectionState == FluxConnectionState.CONNECTED && !progress.isSending,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.flux_test_482b))
                        }
                        Button(
                            onClick = { vm.sendTestBuffer(4096) },
                            enabled = connectionState == FluxConnectionState.CONNECTED && !progress.isSending,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.flux_test_4kb))
                        }
                        Button(
                            onClick = { vm.sendTestBuffer(16384) },
                            enabled = connectionState == FluxConnectionState.CONNECTED && !progress.isSending,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.flux_test_16kb))
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                ) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        label = { Text(stringResource(R.string.flux_send_text_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            vm.sendText(customText)
                            customText = ""
                        },
                        enabled = connectionState == FluxConnectionState.CONNECTED && customText.isNotEmpty() && !progress.isSending,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.flux_send_btn))
                    }
                }
            }
        }

        if (receivedData.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.flux_received_section),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            IconButton(onClick = { vm.clearReceivedData() }) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = null)
                            }
                        }
                        Spacer(Modifier.height(8.dp))

                        receivedData.forEachIndexed { idx, msg ->
                            ReceivedMessageCard(index = idx + 1, msg = msg)
                            if (idx < receivedData.size - 1) {
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceivedMessageCard(
    index: Int,
    msg: FluxReceivedMessage,
) {
    var expanded by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val timeStr = remember(msg.timestampMs) { dateFormat.format(Date(msg.timestampMs)) }
    val textContent = remember(msg.data) { String(msg.data, Charsets.UTF_8) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = "#$index",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "$timeStr  ·  ${msg.data.size} bytes",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = textContent,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun FluxLogsTab(vm: FluxViewModel) {
    val logs by vm.logs.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.flux_nav_logs),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            IconButton(onClick = { vm.clearLogs() }) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null)
            }
        }

        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(16.dp),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(logs) { log ->
                    Text(
                        text = log,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            }
        }
    }
}
