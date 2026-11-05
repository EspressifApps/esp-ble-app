package com.espressif.espbleapp.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.espressif.espbleapp.R
import com.espressif.espbleapp.gmp.FtReason
import com.espressif.espbleapp.gmp.FtTransferState
import com.espressif.espbleapp.flux.FluxLinkState as GmpConnectionState
import com.espressif.espbleapp.viewmodel.GmpFileTransferViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GmpFileTransferRoot(
    vm: GmpFileTransferViewModel = viewModel(),
    onBackToLauncher: (() -> Unit)? = null,
) {
    var tab by remember { mutableIntStateOf(0) }

    val connectionState by vm.connectionState.collectAsStateWithLifecycle()
    val transferState by vm.transferState.collectAsStateWithLifecycle()
    val connectedDevice by vm.connectedDevice.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.file_transfer_title),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        Spacer(Modifier.width(10.dp))

                        val (statusText, badgeColor) = connectionBadge(connectionState)
                        Surface(
                            shape = CircleShape,
                            color = badgeColor.copy(alpha = 0.2f),
                        ) {
                            Text(
                                text = if (connectionState is GmpConnectionState.Ready && connectedDevice != null) {
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
                0 -> GmpScanTab(vm = vm)
                1 -> GmpTransferTab(
                    vm = vm,
                    connectionState = connectionState,
                    transferState = transferState,
                )
                2 -> GmpLogsTab(vm = vm)
            }
        }
    }
}

@Composable
private fun connectionBadge(connectionState: GmpConnectionState): Pair<String, Color> {
    return when (connectionState) {
        GmpConnectionState.Ready -> stringResource(R.string.gmp_stage_ready) to Color(0xFF4CAF50)
        GmpConnectionState.Idle -> stringResource(R.string.flux_status_disconnected) to Color.Gray
        is GmpConnectionState.Failed -> localizedConnectionFailure(connectionState.message) to Color(0xFFF44336)
        GmpConnectionState.Connecting -> stringResource(R.string.gmp_stage_connecting) to Color(0xFFFF9800)
        GmpConnectionState.RequestingMtu -> stringResource(R.string.gmp_stage_requesting_mtu) to Color(0xFFFF9800)
        GmpConnectionState.DiscoveringServices -> {
            stringResource(R.string.gmp_stage_discovering_services) to Color(0xFFFF9800)
        }
        GmpConnectionState.EnablingNotifications -> {
            stringResource(R.string.gmp_stage_enabling_notifications) to Color(0xFFFF9800)
        }
    }
}

@Composable
private fun GmpScanTab(vm: GmpFileTransferViewModel) {
    val devices by vm.scannedDevices.collectAsStateWithLifecycle()
    val isScanning by vm.isScanning.collectAsStateWithLifecycle()
    val connectionState by vm.connectionState.collectAsStateWithLifecycle()
    val connectedDevice by vm.connectedDevice.collectAsStateWithLifecycle()

    var filterGmpService by remember { mutableStateOf(false) }
    val canConnect = connectionState is GmpConnectionState.Idle ||
        connectionState is GmpConnectionState.Failed

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
                        text = stringResource(R.string.gmp_filter_service),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = filterGmpService,
                        onCheckedChange = { filterGmpService = it },
                    )
                }

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        if (isScanning) {
                            vm.stopScan()
                        } else {
                            vm.startScan(filterGmpService)
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

                            if (isCurrent && connectionState is GmpConnectionState.Ready) {
                                OutlinedButton(onClick = { vm.disconnect() }) {
                                    Text(stringResource(R.string.flux_disconnect))
                                }
                            } else {
                                Button(
                                    onClick = { vm.connect(dev) },
                                    enabled = canConnect,
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
private fun GmpTransferTab(
    vm: GmpFileTransferViewModel,
    connectionState: GmpConnectionState,
    transferState: FtTransferState,
) {
    val connectedDevice by vm.connectedDevice.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var sendError by remember { mutableStateOf<String?>(null) }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        selectedFileUri = uri
        sendError = null
    }
    val selectedFile = remember(selectedFileUri) {
        selectedFileUri?.let { querySelectedFileInfo(context, it) }
    }

    val transferring = transferState.isActive()
    val canSend = connectionState is GmpConnectionState.Ready &&
        selectedFileUri != null &&
        transferState.isIdleOrTerminal()

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(transferState) {
        if (transferState is FtTransferState.Sending) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(200)
            }
        }
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
                    when (connectionState) {
                        is GmpConnectionState.Ready -> {
                            if (connectedDevice != null) {
                                Text(
                                    text = stringResource(
                                        R.string.flux_connected_device,
                                        connectedDevice!!.name,
                                        connectedDevice!!.address,
                                    ),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
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
                                    text = stringResource(R.string.gmp_stage_ready),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        is GmpConnectionState.Failed -> {
                            Text(
                                text = localizedConnectionFailure(connectionState.message),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        GmpConnectionState.Idle -> {
                            Text(
                                text = stringResource(R.string.flux_status_disconnected),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        GmpConnectionState.Connecting -> {
                            Text(stringResource(R.string.gmp_stage_connecting))
                        }
                        GmpConnectionState.RequestingMtu -> {
                            Text(stringResource(R.string.gmp_stage_requesting_mtu))
                        }
                        GmpConnectionState.DiscoveringServices -> {
                            Text(stringResource(R.string.gmp_stage_discovering_services))
                        }
                        GmpConnectionState.EnablingNotifications -> {
                            Text(stringResource(R.string.gmp_stage_enabling_notifications))
                        }
                    }
                }
            }
        }

        if (transferState is FtTransferState.Sending || transferState is FtTransferState.Completed) {
            item {
                GmpTransferMetricsCard(transferState = transferState, nowMs = nowMs)
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
                        text = stringResource(R.string.file_transfer_title),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    )
                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { filePickerLauncher.launch("*/*") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.file_select_btn))
                    }

                    Spacer(Modifier.height(8.dp))

                    val fileInfo = selectedFile
                    if (fileInfo != null) {
                        Text(
                            text = if (fileInfo.sizeBytes != null) {
                                stringResource(
                                    R.string.file_selected_info,
                                    fileInfo.name,
                                    fileInfo.sizeBytes,
                                )
                            } else {
                                stringResource(R.string.gmp_selected_file, fileInfo.name)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val uri = selectedFileUri ?: return@Button
                                try {
                                    sendError = null
                                    vm.send(uri)
                                } catch (_: IllegalArgumentException) {
                                    sendError = context.getString(R.string.gmp_error_invalid_name)
                                }
                            },
                            enabled = canSend,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.file_send_btn))
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.file_no_selected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (transferring) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { vm.cancelTransfer() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.gmp_cancel))
                        }
                    }

                    sendError?.let { error ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    GmpTransferStatus(transferState)
                }
            }
        }
    }
}

@Composable
private fun GmpTransferStatus(transferState: FtTransferState) {
    when (val state = transferState) {
        FtTransferState.Idle -> Text(stringResource(R.string.gmp_ready_to_send))
        is FtTransferState.Hashing -> Text(stringResource(R.string.gmp_hashing))
        FtTransferState.Negotiating -> Text(stringResource(R.string.gmp_negotiating))
        is FtTransferState.Sending -> {
            // Progress and confirmed byte counts are already shown in the shared metrics card.
        }
        FtTransferState.Verifying -> Text(stringResource(R.string.gmp_verifying))
        is FtTransferState.Completed -> Text(stringResource(R.string.gmp_complete))
        FtTransferState.Cancelled -> Text(stringResource(R.string.gmp_cancelled))
        is FtTransferState.Failed -> {
            Text(
                text = localizedTransferFailure(state),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun GmpTransferMetricsCard(
    transferState: FtTransferState,
    nowMs: Long,
) {
    val elapsedMs = when (transferState) {
        is FtTransferState.Sending -> (nowMs - transferState.startedAtMs).coerceAtLeast(0)
        is FtTransferState.Completed -> transferState.elapsedMs.coerceAtLeast(0)
        else -> 0L
    }
    val confirmedBytes = when (transferState) {
        is FtTransferState.Sending -> transferState.confirmedBytes
        is FtTransferState.Completed -> transferState.bytes
        else -> 0L
    }
    val totalBytes = when (transferState) {
        is FtTransferState.Sending -> transferState.totalBytes
        is FtTransferState.Completed -> transferState.bytes
        else -> 0L
    }
    val isComplete = transferState is FtTransferState.Completed

    TransferMetricsCard(
        title = stringResource(R.string.gmp_performance_title),
        elapsedTimeMs = elapsedMs,
        averageSpeedBytesPerSecond = confirmedBytes.toDouble() * 1000.0 / elapsedMs.coerceAtLeast(1L),
        dataSizeBytes = totalBytes,
        progress = confirmedBytes.toFloat() / totalBytes.coerceAtLeast(1L),
        status = if (isComplete) stringResource(R.string.gmp_complete) else "传输中…",
        statusColor = if (isComplete) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
        isComplete = isComplete,
        detail = stringResource(R.string.gmp_confirmed_bytes, confirmedBytes, totalBytes),
    )
}

@Composable
private fun GmpLogsTab(vm: GmpFileTransferViewModel) {
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

@Composable
private fun localizedConnectionFailure(message: String): String {
    val lower = message.lowercase()
    return when {
        "below the required minimum" in lower || "mtu negotiation failed" in lower -> {
            stringResource(R.string.gmp_error_low_mtu)
        }
        "service" in lower && "not found" in lower -> {
            stringResource(R.string.gmp_error_missing_service)
        }
        "characteristic" in lower && "not found" in lower -> {
            stringResource(R.string.gmp_error_missing_characteristic)
        }
        else -> message
    }
}

@Composable
private fun localizedTransferFailure(state: FtTransferState.Failed): String {
    val lower = state.message.lowercase()
    return when {
        state.peerReasonCode == FtReason.TIMEOUT || "timed out" in lower -> {
            stringResource(R.string.gmp_error_timeout)
        }
        state.peerReasonCode == FtReason.FILE_TOO_LARGE ||
            state.peerReasonCode == FtReason.NO_SPACE ||
            state.peerReasonCode == FtReason.REJECTED_BY_APP ||
            "peer rejected" in lower -> {
            stringResource(R.string.gmp_peer_rejected)
        }
        else -> state.message
    }
}

private fun FtTransferState.isActive(): Boolean =
    this is FtTransferState.Hashing ||
        this is FtTransferState.Negotiating ||
        this is FtTransferState.Sending ||
        this is FtTransferState.Verifying

private fun FtTransferState.isIdleOrTerminal(): Boolean =
    this is FtTransferState.Idle ||
        this is FtTransferState.Completed ||
        this is FtTransferState.Cancelled ||
        this is FtTransferState.Failed

private data class SelectedFileInfo(
    val name: String,
    val sizeBytes: Long?,
)

private fun querySelectedFileInfo(context: Context, uri: Uri): SelectedFileInfo {
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                cursor.getLong(sizeIndex).takeIf { it >= 0 }
            } else {
                null
            }
            return SelectedFileInfo(
                name = name.ifBlank { uri.lastPathSegment ?: "file" },
                sizeBytes = size,
            )
        }
    }
    return SelectedFileInfo(name = uri.lastPathSegment ?: "file", sizeBytes = null)
}

private fun averageConfirmedKBps(confirmedBytes: Long, elapsedMs: Long): Double {
    val elapsed = elapsedMs.coerceAtLeast(1)
    return (confirmedBytes.toDouble() / 1024.0) / (elapsed.toDouble() / 1000.0)
}
