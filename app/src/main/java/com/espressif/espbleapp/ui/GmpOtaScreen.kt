package com.espressif.espbleapp.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdate
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.espressif.espbleapp.R
import com.espressif.espbleapp.flux.FluxConnectionState
import com.espressif.espbleapp.flux.FluxReceivedMessage
import com.espressif.espbleapp.flux.GmpOtaUuids
import com.espressif.espbleapp.viewmodel.FluxViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GmpOtaRoot(
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
                            text = stringResource(R.string.gmp_ota_title),
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
                    icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                    label = { Text(stringResource(R.string.gmp_ota_title)) },
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
                0 -> GmpOtaScanTab(vm = vm)
                1 -> GmpOtaTransferTab(vm = vm)
                2 -> GmpOtaLogsTab(vm = vm)
            }
        }
    }
}

@Composable
private fun GmpOtaScanTab(vm: FluxViewModel) {
    val devices by vm.scannedDevices.collectAsStateWithLifecycle()
    val isScanning by vm.isScanning.collectAsStateWithLifecycle()
    val connectionState by vm.connectionState.collectAsStateWithLifecycle()
    val connectedDevice by vm.connectedDevice.collectAsStateWithLifecycle()

    var filterService by remember { mutableStateOf(false) }

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
                        text = stringResource(R.string.gmp_ota_filter_service),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = filterService,
                        onCheckedChange = { filterService = it },
                    )
                }

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        if (isScanning) {
                            vm.stopScan()
                        } else {
                            vm.startScan(if (filterService) GmpOtaUuids.SERVICE else null)
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
                                    onClick = { vm.connectGmpOta(dev) },
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
private fun GmpOtaTransferTab(vm: FluxViewModel) {
    val connectionState by vm.connectionState.collectAsStateWithLifecycle()
    val connectedDevice by vm.connectedDevice.collectAsStateWithLifecycle()
    val mtu by vm.mtu.collectAsStateWithLifecycle()
    val progress by vm.gmpOtaProgress.collectAsStateWithLifecycle()
    val receivedData by vm.receivedData.collectAsStateWithLifecycle()

    var selectedBinUri by remember { mutableStateOf<Uri?>(null) }
    var otaClockNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(
        progress.dataTransferStartedAtMs,
        progress.confirmedBytes,
        progress.totalBytes,
        progress.isUploading,
    ) {
        if (
            progress.isUploading && progress.dataTransferStartedAtMs > 0L &&
            progress.confirmedBytes < progress.totalBytes
        ) {
            while (true) {
                otaClockNowMs = System.currentTimeMillis()
                delay(250L)
            }
        } else {
            otaClockNowMs = System.currentTimeMillis()
        }
    }
    val otaElapsedTimeMs = if (
        progress.dataTransferStartedAtMs > 0L && progress.confirmedBytes < progress.totalBytes && progress.isUploading
    ) {
        (otaClockNowMs - progress.dataTransferStartedAtMs).coerceAtLeast(0L)
    } else {
        progress.dataTransferElapsedMs
    }
    val context = LocalContext.current
    val binPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        selectedBinUri = uri
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

        if (progress.totalBytes > 0 || progress.status.isNotEmpty()) {
            item {
                val failed = progress.status.startsWith("OTA 失败")
                val isComplete = !progress.isUploading &&
                    progress.confirmedBytes == progress.totalBytes && progress.totalBytes > 0 && !failed
                val (statusLabel, statusColor) = when {
                    progress.isUploading -> "Updating…" to MaterialTheme.colorScheme.primary
                    failed -> "Failed" to MaterialTheme.colorScheme.error
                    isComplete -> "Device acknowledged" to Color(0xFF4CAF50)
                    else -> "Ready" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                val confirmedBytes = progress.confirmedBytes.coerceAtLeast(0).toLong()
                val totalBytes = progress.totalBytes.coerceAtLeast(0).toLong()
                TransferMetricsCard(
                    title = "GMP OTA Progress",
                    elapsedTimeMs = otaElapsedTimeMs,
                    averageSpeedBytesPerSecond = if (otaElapsedTimeMs > 0L) {
                        confirmedBytes.toDouble() * 1000.0 / otaElapsedTimeMs
                    } else {
                        0.0
                    },
                    dataSizeBytes = totalBytes,
                    progress = confirmedBytes.toFloat() / totalBytes.coerceAtLeast(1L),
                    status = statusLabel,
                    statusColor = statusColor,
                    isComplete = isComplete,
                    detail = "${progress.status}\n设备确认接收：${progress.confirmedBytes} / ${progress.totalBytes} 字节",
                )
            }
        }

        // OTA Firmware Bin Picker Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        text = "GMP OTA Firmware Image (.bin)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    )
                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { binPickerLauncher.launch("*/*") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Select Firmware Binary (.bin)")
                    }

                    Spacer(Modifier.height(8.dp))

                    val uri = selectedBinUri
                    if (uri != null) {
                        val fileName = remember(uri, context) {
                            queryDisplayName(context, uri)
                        }
                        Text(
                            text = "Selected: $fileName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { vm.sendGmpOtaFirmware(context, uri) },
                            enabled = connectionState == FluxConnectionState.CONNECTED && !progress.isUploading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Start GMP OTA Update")
                        }
                    } else {
                        Text(
                            text = "No firmware binary selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                                text = "OTA Responses / Messages",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            IconButton(onClick = { vm.clearReceivedData() }) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = null)
                            }
                        }
                        Spacer(Modifier.height(8.dp))

                        receivedData.forEachIndexed { idx, msg ->
                            GmpOtaMessageCard(index = idx + 1, msg = msg)
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

private fun queryDisplayName(context: Context, uri: Uri): String {
    val providerName = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0) cursor.getString(nameColumn) else null
            } else {
                null
            }
        }
    }.getOrNull()

    return providerName?.takeIf { it.isNotBlank() }
        ?: Uri.decode(uri.lastPathSegment.orEmpty()).substringAfterLast(':').ifBlank { "ota_image.bin" }
}

@Composable
private fun GmpOtaMessageCard(
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
private fun GmpOtaLogsTab(vm: FluxViewModel) {
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
