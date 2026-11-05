package com.espressif.espbleapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.espressif.espbleapp.R
import com.espressif.espbleapp.data.ControlScope
import com.espressif.espbleapp.data.ControlTarget
import com.espressif.espbleapp.data.ControlTargetResolver
import com.espressif.espbleapp.data.ESL_AP_LOCAL_ID
import com.espressif.espbleapp.data.LedConfig
import com.espressif.espbleapp.data.LedRepeatMode
import com.espressif.espbleapp.data.PROVISION_GROUP_CHOICES
import com.espressif.espbleapp.data.ScannedTag
import com.espressif.espbleapp.data.TagRecord
import com.espressif.espbleapp.image.EpaperScaleMode
import com.espressif.espbleapp.image.ImageUploadIds
import com.espressif.espbleapp.settings.AppLanguage
import com.espressif.espbleapp.transport.ApConnectionState
import com.espressif.espbleapp.viewmodel.MainViewModel

@Composable
fun EslAppRoot(
    vm: MainViewModel,
    onBackToLauncher: (() -> Unit)? = null,
) {
    var tab by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val navLabels = listOf(
        stringResource(R.string.nav_connect),
        stringResource(R.string.nav_dashboard),
        stringResource(R.string.nav_tags),
        stringResource(R.string.nav_controls),
        stringResource(R.string.nav_image),
        stringResource(R.string.nav_logs),
        stringResource(R.string.nav_settings),
    )

    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        snackbar.showSnackbar(m.text(context))
        vm.clearMessage()
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (vm.isMock) R.string.title_app_mock else R.string.title_app,
                        ),
                    )
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
                navLabels.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Text((index + 1).toString()) },
                        label = { Text(label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.status_label, connection.toString()),
                style = MaterialTheme.typography.labelLarge,
            )
            if (busy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
            when (tab) {
                0 -> ConnectScreen(vm)
                1 -> DashboardScreen(vm)
                2 -> TagListScreen(vm)
                3 -> ControlsScreen(vm)
                4 -> ImageScreen(vm)
                5 -> LogsScreen(vm)
                6 -> SettingsScreen(vm)
            }
        }
    }
}

@Composable
fun ConnectScreen(vm: MainViewModel) {
    val devices by vm.scannedDevices.collectAsStateWithLifecycle()
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val hello by vm.helloInfo.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.startScan() }, enabled = connection != ApConnectionState.Scanning) {
                Text(stringResource(R.string.connect_scan_ap))
            }
            OutlinedButton(onClick = { vm.stopScan() }) {
                Text(stringResource(R.string.connect_stop))
            }
            if (vm.isMock) {
                Button(onClick = { vm.connectMock() }) {
                    Text(stringResource(R.string.connect_mock))
                }
            }
            OutlinedButton(onClick = { vm.disconnect() }) {
                Text(stringResource(R.string.connect_disconnect))
            }
        }
        hello?.let {
            Text(stringResource(R.string.connect_protocol_info, it.protocol, it.fwVer, it.deviceName))
            Text(stringResource(R.string.connect_capabilities, it.capabilities.joinToString()))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices, key = { it.address }) { d ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.connect(d.address) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(d.name ?: d.address)
                        Text(stringResource(R.string.connect_rssi, d.address, d.rssi))
                    }
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(vm: MainViewModel) {
    val status by vm.apStatus.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.refreshStatus() }) {
            Text(stringResource(R.string.dashboard_refresh))
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.dashboard_started, status.started, status.scanning))
                Text(stringResource(R.string.dashboard_pawr, status.pawrActive, status.tagCount))
                Text(stringResource(R.string.dashboard_phone, status.selectedIndex, status.phoneLink))
                Text(stringResource(R.string.dashboard_last_error, status.lastError ?: "-"))
                if (status.recoveryPhase != "unknown") {
                    Text(
                        stringResource(
                            R.string.dashboard_recovery,
                            status.bootId.ifBlank { "-" },
                            status.recoveryPhase,
                            status.health,
                        ),
                    )
                }
                status.recommendedAction?.let {
                    Text(stringResource(R.string.dashboard_recommended_action, it))
                }
            }
        }
        Text(stringResource(R.string.dashboard_tasks), style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(tasks.take(20), key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(stringResource(R.string.dashboard_task_line, t.kind, t.status))
                        Text(t.label)
                        if (t.detail.isNotBlank()) Text(t.detail)
                    }
                }
            }
        }
    }
}

@Composable
fun TagListScreen(vm: MainViewModel) {
    val tags by vm.tags.collectAsStateWithLifecycle()
    val scannedTags by vm.scannedTags.collectAsStateWithLifecycle()
    val selected by vm.selectedTarget.collectAsStateWithLifecycle()
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    var pendingProvision by remember { mutableStateOf<ScannedTag?>(null) }

    /* Status refresh on reconnect is handled by EslRpcClient.onTransportReady(). */

    val inventoryAddrs = remember(tags) {
        tags.map { it.bleAddr.lowercase() }.toSet()
    }
    val provisionable = remember(scannedTags, inventoryAddrs) {
        scannedTags.filter { tag -> tag.bleAddr.lowercase() !in inventoryAddrs }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.refreshStatus() }) {
                Text(stringResource(R.string.tags_refresh))
            }
            Button(onClick = { vm.scanTags() }) {
                Text(stringResource(R.string.tags_scan))
            }
            Button(onClick = { vm.stopScanTags() }) {
                Text(stringResource(R.string.tags_scan_stop))
            }
        }
        Spacer(Modifier.height(8.dp))

        if (provisionable.isNotEmpty()) {
            Text(stringResource(R.string.tags_scanned_section), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            provisionable.forEach { tag ->
                ScannedTagCard(tag) { pendingProvision = tag }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(4.dp))
        }

        Text(stringResource(R.string.tags_inventory_section), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        if (connection == ApConnectionState.Ready) {
            ApTargetCard(selected is ControlTarget.Ap) { vm.selectAp() }
            Spacer(Modifier.height(8.dp))
        }
        tags.forEach { tag ->
            val selectedTag = selected as? ControlTarget.Tag
            val isSelected = selectedTag != null &&
                selectedTag.eslId == tag.eslId &&
                selectedTag.groupId == tag.groupId
            TagCard(tag, isSelected) { vm.selectTag(tag.eslId, tag.groupId) }
            Spacer(Modifier.height(8.dp))
        }
    }

    val provisionTag = pendingProvision
    if (provisionTag != null) {
        AlertDialog(
            onDismissRequest = { pendingProvision = null },
            title = { Text(stringResource(R.string.tags_provision_group_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.tags_provision_group_body, provisionTag.bleAddr))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PROVISION_GROUP_CHOICES.take(2).forEach { groupId ->
                            Button(
                                onClick = {
                                    pendingProvision = null
                                    vm.provisionScannedTag(provisionTag, groupId)
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.tags_provision_group_btn, groupId))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PROVISION_GROUP_CHOICES.drop(2).forEach { groupId ->
                            Button(
                                onClick = {
                                    pendingProvision = null
                                    vm.provisionScannedTag(provisionTag, groupId)
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.tags_provision_group_btn, groupId))
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingProvision = null }) {
                    Text(stringResource(R.string.tags_provision_group_cancel))
                }
            },
        )
    }
}

@Composable
private fun ScannedTagCard(tag: ScannedTag, onProvision: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tag.bleAddr, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.tags_rssi_associated, tag.rssi, tag.associated))
            }
            Button(onClick = onProvision) {
                Text(stringResource(R.string.tags_provision))
            }
        }
    }
}

@Composable
private fun ApTargetCard(selected: Boolean, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${if (selected) "▶ " else ""}${stringResource(R.string.tags_ap_title)}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.tags_ap_detail, ESL_AP_LOCAL_ID))
            Text(
                stringResource(R.string.tags_ap_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TagCard(tag: TagRecord, selected: Boolean, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${if (selected) "▶ " else ""}${tag.bleAddr}",
                style = MaterialTheme.typography.titleMedium,
            )
            val stateLabel = if (tag.productState != "unknown") {
                tag.productState
            } else {
                tag.state
            }
            Text(
                stringResource(R.string.tag_detail, tag.eslId, tag.groupId, stateLabel),
            )
            if (tag.failureReason != null || tag.provisionPhase !in listOf("unknown", "active")) {
                Text(
                    stringResource(
                        R.string.tag_recovery_detail,
                        tag.provisionPhase,
                        tag.failureReason ?: "-",
                    ),
                )
            }
            Text(
                stringResource(
                    R.string.tag_capabilities,
                    tag.currentImageIndex,
                    tag.hasDisplay,
                    tag.hasLed,
                    tag.hasSensor,
                ),
            )
        }
    }
}

@Composable
fun ControlsScreen(vm: MainViewModel) {
    val tags by vm.tags.collectAsStateWithLifecycle()
    val selected by vm.selectedTarget.collectAsStateWithLifecycle()
    val scope by vm.controlScope.collectAsStateWithLifecycle()
    val hello by vm.helloInfo.collectAsStateWithLifecycle()
    val ledConfig by vm.ledConfig.collectAsStateWithLifecycle()
    val isAp = selected is ControlTarget.Ap
    val tag = ControlTargetResolver.findTag(selected, tags)
    val groupBroadcastCapable = hello?.supportsGroupBroadcast == true
    val groupOnline = tag != null && vm.groupHasOnlineTag(tag.groupId)
    val useGroup = !isAp && groupBroadcastCapable && scope == ControlScope.Group
    val selectedControllable = tag?.isControllable == true
    val pawrEnabled = when {
        isAp -> false
        useGroup -> groupOnline
        else -> selectedControllable
    }
    val displayEnabled = when {
        isAp -> true
        useGroup -> true
        else -> selectedControllable
    }
    var showUnassociateConfirm by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(groupBroadcastCapable, isAp) {
        if ((!groupBroadcastCapable || isAp) && scope == ControlScope.Group) {
            vm.setControlScope(ControlScope.Selected)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            when {
                isAp -> stringResource(R.string.controls_current_ap, ESL_AP_LOCAL_ID)
                tag != null -> stringResource(
                    R.string.controls_current_tag,
                    tag.bleAddr,
                    tag.eslId,
                    tag.groupId,
                )
                else -> stringResource(R.string.controls_no_tag)
            },
        )
        if (groupBroadcastCapable) {
            Text(
                stringResource(R.string.controls_scope_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = scope == ControlScope.Selected,
                    onClick = { vm.setControlScope(ControlScope.Selected) },
                    label = { Text(stringResource(R.string.controls_scope_selected)) },
                )
                FilterChip(
                    selected = scope == ControlScope.Group,
                    onClick = { vm.setControlScope(ControlScope.Group) },
                    enabled = tag != null && !isAp,
                    label = { Text(stringResource(R.string.controls_scope_group)) },
                )
            }
            if (isAp) {
                Text(
                    stringResource(R.string.controls_ap_scope_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (useGroup && tag != null) {
                Text(
                    stringResource(R.string.controls_group_target, tag.groupId),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (isAp) {
            Text(
                stringResource(R.string.controls_ap_pawr_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (tag != null && !pawrEnabled) {
            Text(
                if (useGroup) {
                    stringResource(R.string.controls_group_display_only, tag.groupId)
                } else {
                    stringResource(
                        R.string.controls_not_ready,
                        if (tag.productState != "unknown") tag.productState else tag.state,
                    )
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        CollapsibleCommandSection(
            title = stringResource(R.string.cmd_ping_title),
            summary = stringResource(R.string.cmd_ping_summary),
        ) {
            Button(
                onClick = { vm.pingSelected() },
                enabled = pawrEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.cmd_ping_send))
            }
        }

        CollapsibleCommandSection(
            title = stringResource(R.string.cmd_led_title),
            summary = ledConfigSummary(ledConfig),
        ) {
            LedConfigContent(
                config = ledConfig,
                ledAvailable = pawrEnabled && (useGroup || tag?.hasLed != false),
                onPreset = { preset, mode -> vm.applyLedPreset(preset.red, preset.green, preset.blue, mode) },
                onUpdate = { vm.updateLedConfig(it) },
                onApply = { vm.applyLedSelected() },
            )
        }

        CollapsibleCommandSection(
            title = stringResource(R.string.cmd_refresh_title),
            summary = stringResource(R.string.cmd_refresh_summary),
        ) {
            Button(
                onClick = { vm.refreshSelected() },
                enabled = displayEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.cmd_refresh_send))
            }
        }

        CollapsibleCommandSection(
            title = stringResource(R.string.cmd_display_title),
            summary = stringResource(
                if (isAp) R.string.cmd_display_summary_ap else R.string.cmd_display_summary,
            ),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0..3).forEach { idx ->
                    FilterChip(
                        selected = false,
                        onClick = { vm.displaySelected(idx) },
                        enabled = displayEnabled,
                        label = { Text(stringResource(R.string.cmd_display_img, idx)) },
                    )
                }
            }
        }

        CollapsibleCommandSection(
            title = stringResource(R.string.cmd_unassociate_title),
            summary = stringResource(R.string.cmd_unassociate_summary),
        ) {
            OutlinedButton(
                onClick = { showUnassociateConfirm = true },
                enabled = tag != null && !isAp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.cmd_unassociate_send))
            }
        }
    }

    if (showUnassociateConfirm && tag != null) {
        AlertDialog(
            onDismissRequest = { showUnassociateConfirm = false },
            title = { Text(stringResource(R.string.cmd_unassociate_confirm_title)) },
            text = {
                Text(stringResource(R.string.cmd_unassociate_confirm_body, tag.eslId))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnassociateConfirm = false
                        vm.unassociateSelected()
                    },
                ) {
                    Text(stringResource(R.string.cmd_unassociate_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnassociateConfirm = false }) {
                    Text(stringResource(R.string.cmd_unassociate_confirm_cancel))
                }
            },
        )
    }
}

@Composable
private fun ledConfigSummary(config: LedConfig): String {
    val mode = when (config.repeatMode) {
        LedRepeatMode.SteadyOn -> stringResource(R.string.led_summary_mode_steady)
        LedRepeatMode.SteadyOff -> stringResource(R.string.led_summary_mode_off)
        LedRepeatMode.Timed -> stringResource(R.string.led_summary_mode_timed, config.durationSeconds)
        LedRepeatMode.Blink -> stringResource(R.string.led_summary_mode_blink, config.durationSeconds)
    }
    return stringResource(
        R.string.led_summary,
        config.ledIndex,
        config.colorRed,
        config.colorGreen,
        config.colorBlue,
        config.brightness,
        mode,
    )
}

@Composable
private fun CollapsibleCommandSection(
    title: String,
    summary: String? = null,
    defaultExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(defaultExpanded) }
    val expandLabel = stringResource(R.string.expand)
    val collapseLabel = stringResource(R.string.collapse)
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (!summary.isNullOrBlank()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) collapseLabel else expandLabel,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun LedConfigContent(
    config: LedConfig,
    ledAvailable: Boolean,
    onPreset: (LedColorPreset, LedRepeatMode) -> Unit,
    onUpdate: ((LedConfig) -> LedConfig) -> Unit,
    onApply: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.led_color_presets), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LedPresetChip(LedColorPreset.Red, config) {
                onPreset(LedColorPreset.Red, LedRepeatMode.SteadyOn)
            }
            LedPresetChip(LedColorPreset.Green, config) {
                onPreset(LedColorPreset.Green, LedRepeatMode.SteadyOn)
            }
            LedPresetChip(LedColorPreset.Blue, config) {
                onPreset(LedColorPreset.Blue, LedRepeatMode.SteadyOn)
            }
            LedPresetChip(LedColorPreset.White, config) {
                onPreset(LedColorPreset.White, LedRepeatMode.SteadyOn)
            }
            LedPresetChip(LedColorPreset.Off, config) {
                onPreset(LedColorPreset.Off, LedRepeatMode.SteadyOff)
            }
        }
        LedLevelRow(stringResource(R.string.led_level_red), config.colorRed) {
            onUpdate { c -> c.copy(colorRed = it, repeatMode = LedRepeatMode.SteadyOn) }
        }
        LedLevelRow(stringResource(R.string.led_level_green), config.colorGreen) {
            onUpdate { c -> c.copy(colorGreen = it, repeatMode = LedRepeatMode.SteadyOn) }
        }
        LedLevelRow(stringResource(R.string.led_level_blue), config.colorBlue) {
            onUpdate { c -> c.copy(colorBlue = it, repeatMode = LedRepeatMode.SteadyOn) }
        }
        LedLevelRow(stringResource(R.string.led_level_brightness), config.brightness) {
            onUpdate { c -> c.copy(brightness = it) }
        }
        Text(stringResource(R.string.led_mode), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = config.repeatMode == LedRepeatMode.SteadyOn,
                onClick = { onUpdate { it.copy(repeatMode = LedRepeatMode.SteadyOn) } },
                label = { Text(stringResource(R.string.led_mode_steady)) },
            )
            FilterChip(
                selected = config.repeatMode == LedRepeatMode.SteadyOff,
                onClick = { onUpdate { it.copy(repeatMode = LedRepeatMode.SteadyOff) } },
                label = { Text(stringResource(R.string.led_mode_off)) },
            )
            FilterChip(
                selected = config.repeatMode == LedRepeatMode.Timed,
                onClick = { onUpdate { it.copy(repeatMode = LedRepeatMode.Timed) } },
                label = { Text(stringResource(R.string.led_mode_timed)) },
            )
            FilterChip(
                selected = config.repeatMode == LedRepeatMode.Blink,
                onClick = {
                    onUpdate {
                        it.copy(
                            repeatMode = LedRepeatMode.Blink,
                            durationSeconds = if (it.durationSeconds <= 0) 10 else it.durationSeconds,
                            patternHex = "AAAAAAAAAA",
                            bitOffPeriod = 125,
                            bitOnPeriod = 125,
                        )
                    }
                },
                label = { Text(stringResource(R.string.led_mode_blink)) },
            )
        }
        if (config.repeatMode == LedRepeatMode.Timed || config.repeatMode == LedRepeatMode.Blink) {
            Text(
                stringResource(
                    if (config.repeatMode == LedRepeatMode.Blink) {
                        R.string.led_blink_duration
                    } else {
                        R.string.led_duration
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(3, 5, 10, 30).forEach { sec ->
                    FilterChip(
                        selected = config.durationSeconds == sec,
                        onClick = { onUpdate { it.copy(durationSeconds = sec) } },
                        label = { Text(stringResource(R.string.led_duration_sec, sec)) },
                    )
                }
            }
        }
        if (config.repeatMode == LedRepeatMode.Blink) {
            Text(
                stringResource(R.string.led_blink_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = onApply,
            enabled = ledAvailable,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.led_send))
        }
        if (!ledAvailable) {
            Text(stringResource(R.string.led_unavailable), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private enum class LedColorPreset(val red: Int, val green: Int, val blue: Int) {
    Red(3, 0, 0),
    Green(0, 3, 0),
    Blue(0, 0, 3),
    White(3, 3, 3),
    Off(0, 0, 0),
}

@Composable
private fun LedPresetChip(
    preset: LedColorPreset,
    config: LedConfig,
    onClick: () -> Unit,
) {
    val label = when (preset) {
        LedColorPreset.Red -> stringResource(R.string.led_red)
        LedColorPreset.Green -> stringResource(R.string.led_green)
        LedColorPreset.Blue -> stringResource(R.string.led_blue)
        LedColorPreset.White -> stringResource(R.string.led_white)
        LedColorPreset.Off -> stringResource(R.string.led_off)
    }
    val selected = when (preset) {
        LedColorPreset.Red -> config.colorRed == 3 && config.colorGreen == 0 && config.colorBlue == 0 &&
            config.repeatMode != LedRepeatMode.SteadyOff
        LedColorPreset.Green -> config.colorRed == 0 && config.colorGreen == 3 && config.colorBlue == 0 &&
            config.repeatMode != LedRepeatMode.SteadyOff
        LedColorPreset.Blue -> config.colorRed == 0 && config.colorGreen == 0 && config.colorBlue == 3 &&
            config.repeatMode != LedRepeatMode.SteadyOff
        LedColorPreset.White -> config.colorRed == 3 && config.colorGreen == 3 && config.colorBlue == 3 &&
            config.repeatMode != LedRepeatMode.SteadyOff
        LedColorPreset.Off -> config.repeatMode == LedRepeatMode.SteadyOff
    }
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun LedLevelRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(0.35f))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (0..3).forEach { level ->
                FilterChip(
                    selected = value == level,
                    onClick = { onValueChange(level) },
                    label = { Text("$level") },
                )
            }
        }
    }
}

@Composable
fun ImageScreen(vm: MainViewModel) {
    val edit by vm.imageEdit.collectAsStateWithLifecycle()
    val badgeEdit by vm.badgeEdit.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val selectedTarget by vm.selectedTarget.collectAsStateWithLifecycle()
    val hello by vm.helloInfo.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val selectedTag = ControlTargetResolver.findTag(selectedTarget, tags)
    val canUploadToAp = hello?.supportsBadgeImage == true
    val canTransferToTag = selectedTag?.supportsBadgeImage == true
    val context = LocalContext.current
    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        // Open the stream on the Activity result path — Photo Picker temporary
        // grants are unreliable if deferred to Application/ViewModel later.
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (t: Throwable) {
            vm.onImagePickFailed(t.message ?: t::class.java.simpleName)
            return@rememberLauncherForActivityResult
        }
        if (stream == null) {
            vm.onImagePickFailed("Unable to open image: $uri")
            return@rememberLauncherForActivityResult
        }
        vm.onImagePicked(uri.toString(), stream)
    }
    val pickBadgeMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (t: Throwable) {
            vm.onBadgeImagePickFailed(t.message ?: t::class.java.simpleName)
            return@rememberLauncherForActivityResult
        }
        if (stream == null) {
            vm.onBadgeImagePickFailed("Unable to open image: $uri")
            return@rememberLauncherForActivityResult
        }
        vm.onBadgeImagePicked(uri.toString(), stream)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BadgeUploadSection(
            edit = badgeEdit,
            busy = busy,
            canUploadToAp = canUploadToAp,
            canTransferToTag = canTransferToTag,
            onPick = {
                pickBadgeMedia.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onClear = vm::clearBadgeImage,
            onSlotChanged = vm::setBadgeSlot,
            onUploadAp = vm::uploadBadgeToAp,
            onUploadTag = vm::uploadBadgeAndTransfer,
        )

        EpaperTagSection(
            edit = edit,
            busy = busy,
            onPick = {
                pickMedia.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onClear = vm::clearPickedImage,
            onSlotChanged = vm::setImageSlot,
            onScaleMode = vm::setImageScaleMode,
            onDither = vm::setImageDither,
            onInvert = vm::setImageInvert,
            onThreshold = vm::setImageThreshold,
            onUploadCustom = vm::uploadCustomAndTransfer,
            onUploadDemo = { vm.uploadDemoAndTransfer(edit.imageIndex) },
        )
    }
}

@Composable
private fun EpaperTagSection(
    edit: com.espressif.espbleapp.data.ImageEditState,
    busy: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
    onSlotChanged: (Int) -> Unit,
    onScaleMode: (EpaperScaleMode) -> Unit,
    onDither: (Boolean) -> Unit,
    onInvert: (Boolean) -> Unit,
    onThreshold: (Int) -> Unit,
    onUploadCustom: () -> Unit,
    onUploadDemo: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.image_epaper_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.image_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.image_protocol),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.image_slot_hint, ImageUploadIds.customImageId(edit.imageIndex)),
                style = MaterialTheme.typography.bodySmall,
            )

            Text(stringResource(R.string.image_slot_label), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0..3).forEach { slot ->
                    FilterChip(
                        selected = edit.imageIndex == slot,
                        onClick = { onSlotChanged(slot) },
                        label = { Text("$slot") },
                    )
                }
            }

            Button(
                onClick = onPick,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.image_pick_btn))
            }

            if (edit.sourceUri != null) {
                OutlinedButton(
                    onClick = onClear,
                    enabled = !busy && !edit.converting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.image_clear_btn))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = edit.scaleModeOrdinal == EpaperScaleMode.CenterCrop.ordinal,
                    onClick = { onScaleMode(EpaperScaleMode.CenterCrop) },
                    label = { Text(stringResource(R.string.image_scale_crop)) },
                )
                FilterChip(
                    selected = edit.scaleModeOrdinal == EpaperScaleMode.FitCenter.ordinal,
                    onClick = { onScaleMode(EpaperScaleMode.FitCenter) },
                    label = { Text(stringResource(R.string.image_scale_fit)) },
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.image_dither))
                Switch(
                    checked = edit.dither,
                    onCheckedChange = onDither,
                    enabled = edit.sourceUri != null && !edit.converting,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.image_invert))
                Switch(
                    checked = edit.invert,
                    onCheckedChange = onInvert,
                    enabled = edit.sourceUri != null && !edit.converting,
                )
            }

            Text(stringResource(R.string.image_threshold, edit.threshold))
            Slider(
                value = edit.threshold.toFloat(),
                onValueChange = { onThreshold(it.toInt()) },
                valueRange = 0f..255f,
                enabled = edit.sourceUri != null && !edit.converting && !edit.dither,
            )

            Text(stringResource(R.string.image_preview_title), style = MaterialTheme.typography.titleSmall)
            val preview = edit.preview
            if (preview != null && !preview.isRecycled) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = stringResource(R.string.image_preview_title),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(296f / 128f)
                        .border(1.dp, MaterialTheme.colorScheme.outline),
                )
            } else {
                Text(
                    stringResource(R.string.image_preview_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (edit.converting) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.image_converting))
            }
            edit.error?.let { err ->
                Text(
                    stringResource(R.string.image_convert_error, err),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = onUploadCustom,
                enabled = !busy && edit.readyToUpload,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.image_upload_custom_btn))
            }

            OutlinedButton(
                onClick = onUploadDemo,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.image_upload_btn))
            }
        }
    }
}

@Composable
private fun BadgeUploadSection(
    edit: com.espressif.espbleapp.data.BadgeEditState,
    busy: Boolean,
    canUploadToAp: Boolean,
    canTransferToTag: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
    onSlotChanged: (Int) -> Unit,
    onUploadAp: () -> Unit,
    onUploadTag: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.badge_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.badge_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.badge_slot_label), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0..3).forEach { slot ->
                    FilterChip(
                        selected = edit.slotIndex == slot,
                        onClick = { onSlotChanged(slot) },
                        label = { Text("$slot") },
                    )
                }
            }
            Button(
                onClick = onPick,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.badge_pick_btn))
            }
            if (edit.sourceUri != null) {
                OutlinedButton(
                    onClick = onClear,
                    enabled = !busy && !edit.converting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.image_clear_btn))
                }
            }
            val preview = edit.preview
            if (preview != null && !preview.isRecycled) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = stringResource(R.string.badge_preview_title),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .border(1.dp, MaterialTheme.colorScheme.outline),
                )
            } else {
                Text(
                    stringResource(R.string.image_preview_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (edit.converting) LinearProgressIndicator(Modifier.fillMaxWidth())
            edit.error?.let { error ->
                Text(
                    stringResource(R.string.image_convert_error, error),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!canUploadToAp) {
                Text(
                    stringResource(R.string.badge_ap_waiting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onUploadAp,
                enabled = !busy && canUploadToAp && edit.readyToUpload,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.badge_upload_ap_btn))
            }
            if (canTransferToTag) {
                OutlinedButton(
                    onClick = onUploadTag,
                    enabled = !busy && edit.readyToUpload,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.badge_upload_btn))
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val language by vm.appLanguage.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.settings_language_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LanguageOption(
                    label = stringResource(R.string.settings_language_system),
                    selected = language == AppLanguage.System,
                    onClick = { vm.setAppLanguage(AppLanguage.System) },
                )
                LanguageOption(
                    label = stringResource(R.string.settings_language_en),
                    selected = language == AppLanguage.English,
                    onClick = { vm.setAppLanguage(AppLanguage.English) },
                )
                LanguageOption(
                    label = stringResource(R.string.settings_language_zh),
                    selected = language == AppLanguage.Chinese,
                    onClick = { vm.setAppLanguage(AppLanguage.Chinese) },
                )
            }
        }
    }
}

@Composable
private fun LanguageOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun LogsScreen(vm: MainViewModel) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(logs, key = { "${it.timestampMs}-${it.message}" }) { line ->
            Text("[${line.level}] ${line.message}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
