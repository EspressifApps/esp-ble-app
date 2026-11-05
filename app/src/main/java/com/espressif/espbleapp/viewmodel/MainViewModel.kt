package com.espressif.espbleapp.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.InputStream
import com.espressif.espbleapp.EspBleApp
import com.espressif.espbleapp.R
import com.espressif.espbleapp.data.ApStatus
import com.espressif.espbleapp.data.BadgeEditState
import com.espressif.espbleapp.data.ControlResolveFail
import com.espressif.espbleapp.data.ControlResolveResult
import com.espressif.espbleapp.data.ControlScope
import com.espressif.espbleapp.data.ControlTarget
import com.espressif.espbleapp.data.ControlTargetResolver
import com.espressif.espbleapp.data.ESL_AP_LOCAL_ID
import com.espressif.espbleapp.data.ESL_GROUP_BROADCAST_ID
import com.espressif.espbleapp.data.HelloInfo
import com.espressif.espbleapp.data.ImageEditState
import com.espressif.espbleapp.data.LedConfig
import com.espressif.espbleapp.data.LedRepeatMode
import com.espressif.espbleapp.data.LogLine
import com.espressif.espbleapp.data.PROVISION_GROUP_CHOICES
import com.espressif.espbleapp.data.ScannedTag
import com.espressif.espbleapp.data.TagRecord
import com.espressif.espbleapp.data.TaskRecord
import com.espressif.espbleapp.image.BinaryImageEncoder
import com.espressif.espbleapp.image.BadgeImageEncoder
import com.espressif.espbleapp.image.EpaperComposeOptions
import com.espressif.espbleapp.image.EpaperFrameComposer
import com.espressif.espbleapp.image.EpaperScaleMode
import com.espressif.espbleapp.image.ImageBitmapLoader
import com.espressif.espbleapp.image.ImageUploadIds
import com.espressif.espbleapp.protocol.EslCommands
import com.espressif.espbleapp.protocol.EslErrors
import com.espressif.espbleapp.protocol.EslEvents
import com.espressif.espbleapp.protocol.EslRpcException
import com.espressif.espbleapp.protocol.boolOrNull
import com.espressif.espbleapp.protocol.string
import com.espressif.espbleapp.repository.ApRepository
import com.espressif.espbleapp.settings.AppLanguage
import com.espressif.espbleapp.settings.AppSettingsRepository
import com.espressif.espbleapp.transport.ApConnectionState
import com.espressif.espbleapp.transport.BleDeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ApRepository(app)
    private val settingsRepo: AppSettingsRepository = (app as EspBleApp).settings

    val connectionState: StateFlow<ApConnectionState> = repo.transport.connectionState
    val scannedDevices: StateFlow<List<BleDeviceInfo>> = repo.transport.scannedDevices
    val helloInfo: StateFlow<HelloInfo?> = repo.rpc.helloInfo
    val apStatus: StateFlow<ApStatus> = repo.rpc.apStatus
    val tags: StateFlow<List<TagRecord>> = repo.rpc.tags
    val scannedTags: StateFlow<List<ScannedTag>> = repo.rpc.scannedTags
    val tasks: StateFlow<List<TaskRecord>> = repo.rpc.tasks
    val logs: StateFlow<List<LogLine>> = repo.rpc.logs
    val appLanguage: StateFlow<AppLanguage> = settingsRepo.language.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppLanguage.System,
    )

    private val _selectedTarget = MutableStateFlow<ControlTarget>(ControlTarget.Ap)
    val selectedTarget: StateFlow<ControlTarget> = _selectedTarget.asStateFlow()

    private val _controlScope = MutableStateFlow(ControlScope.Selected)
    val controlScope: StateFlow<ControlScope> = _controlScope.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private val _ledConfig = MutableStateFlow(LedConfig())
    val ledConfig: StateFlow<LedConfig> = _ledConfig.asStateFlow()

    private val _imageEdit = MutableStateFlow(ImageEditState())
    val imageEdit: StateFlow<ImageEditState> = _imageEdit.asStateFlow()

    private val _badgeEdit = MutableStateFlow(BadgeEditState())
    val badgeEdit: StateFlow<BadgeEditState> = _badgeEdit.asStateFlow()

    private var sourceBitmap: Bitmap? = null
    private var composeJob: Job? = null
    private var badgeSourceBitmap: Bitmap? = null
    private var badgeComposeJob: Job? = null

    val isMock: Boolean = repo.isMock

    private val selectedTag: TagRecord?
        get() = ControlTargetResolver.findTag(_selectedTarget.value, tags.value)

    private val appContext get() = getApplication<Application>()

    init {
        repo.start()
        connectionState.stateIn(viewModelScope, SharingStarted.Eagerly, ApConnectionState.Idle)
        viewModelScope.launch {
            tags.collect { list ->
                _selectedTarget.value = ControlTargetResolver.retainOrFallback(_selectedTarget.value, list)
            }
        }
        viewModelScope.launch {
            repo.rpc.events.collect { event ->
                if (event.name != EslEvents.COMMAND_RESULT) return@collect
                val cmd = event.raw.string("cmd")
                if (cmd != EslCommands.DISPLAY_IMAGE && cmd != EslCommands.REFRESH_DISPLAY) {
                    return@collect
                }
                if (event.raw.boolOrNull("ap_displayed") == false) {
                    _message.value = UiMessage.res(R.string.msg_ap_slot_skipped)
                }
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun selectAp() {
        _selectedTarget.value = ControlTarget.Ap
        _controlScope.value = ControlScope.Selected
    }

    fun selectTag(eslId: Int, groupId: Int) {
        _selectedTarget.value = ControlTarget.Tag(eslId, groupId)
    }

    fun setControlScope(scope: ControlScope) {
        if (scope == ControlScope.Group &&
            (!supportsGroupBroadcast || _selectedTarget.value is ControlTarget.Ap)
        ) {
            _controlScope.value = ControlScope.Selected
            return
        }
        _controlScope.value = scope
    }

    val supportsGroupBroadcast: Boolean
        get() = helloInfo.value?.supportsGroupBroadcast == true

    val supportsApBadgeImage: Boolean
        get() = helloInfo.value?.supportsBadgeImage == true

    fun groupHasOnlineTag(groupId: Int): Boolean =
        tags.value.any { it.groupId == groupId && it.isControllable }

    fun setAppLanguage(language: AppLanguage) {
        viewModelScope.launch {
            settingsRepo.setLanguage(language)
        }
    }

    fun startScan() = launchBusy(R.string.busy_scan_devices) {
        repo.transport.startScan()
    }

    fun stopScan() = launchBusy(R.string.busy_stop_scan) {
        repo.transport.stopScan()
    }

    fun connect(address: String) = launchBusy(R.string.busy_connect, address) {
        repo.transport.connect(address)
        // hello + status refresh run in EslRpcClient.onTransportReady() for every Ready session
    }

    fun connectMock() {
        val addr = scannedDevices.value.firstOrNull()?.address ?: "AA:BB:CC:DD:EE:FF"
        connect(addr)
    }

    fun disconnect() = launchBusy(R.string.busy_disconnect) {
        repo.transport.disconnect()
    }

    fun refreshStatus() = launchBusy(R.string.busy_refresh_status) {
        repo.rpc.getApStatus()
        repo.rpc.listTags()
    }

    /** Sole entry point for AP tag discovery; bound to Tags page Scan button only. */
    fun scanTags() = launchBusy(R.string.busy_esl_scan) {
        repo.rpc.scanStart()
    }

    fun stopScanTags() = launchBusy(R.string.busy_esl_scan_stop) {
        repo.rpc.scanStop()
    }

    fun provisionScannedTag(tag: ScannedTag, groupId: Int) = launchBusy(R.string.busy_provision, tag.bleAddr) {
        require(groupId in PROVISION_GROUP_CHOICES) { "groupId must be 0..3" }
        val result = repo.rpc.provisionTag(tag.bleAddr, tag.addrType, groupId = groupId)
        _message.value = UiMessage.res(R.string.msg_provision_started, result.eslId, result.groupId)
    }

    fun pingSelected() = runOnPawrTarget { eslId, groupId, _ ->
        repo.rpc.pingTag(eslId, groupId)
    }

    fun updateLedConfig(transform: (LedConfig) -> LedConfig) {
        _ledConfig.value = transform(_ledConfig.value)
    }

    fun applyLedPreset(red: Int, green: Int, blue: Int, mode: LedRepeatMode = LedRepeatMode.SteadyOn) {
        _ledConfig.value = _ledConfig.value.copy(
            colorRed = red.coerceIn(0, 3),
            colorGreen = green.coerceIn(0, 3),
            colorBlue = blue.coerceIn(0, 3),
            repeatMode = mode,
        )
    }

    fun applyLedSelected() = runOnPawrTarget { eslId, groupId, _ ->
        val cfg = _ledConfig.value
        repo.rpc.ledControl(
            eslId = eslId,
            groupId = groupId,
            ledIndex = cfg.ledIndex,
            colorRed = cfg.colorRed,
            colorGreen = cfg.colorGreen,
            colorBlue = cfg.colorBlue,
            brightness = cfg.brightness,
            repeatType = cfg.repeatType,
            repeatsDuration = cfg.repeatsDuration,
            patternHex = if (cfg.usesFlashingPattern) cfg.patternHex else null,
            bitOffPeriod = if (cfg.usesFlashingPattern) cfg.bitOffPeriod else null,
            bitOnPeriod = if (cfg.usesFlashingPattern) cfg.bitOnPeriod else null,
        )
    }

    fun displaySelected(imageIndex: Int) = runOnDisplayTarget { eslId, groupId, _ ->
        sendApAwareDisplay(eslId) {
            repo.rpc.displayImage(eslId, groupId, imageIndex = imageIndex)
        }
    }

    fun refreshSelected() = runOnDisplayTarget { eslId, groupId, _ ->
        sendApAwareDisplay(eslId) {
            repo.rpc.refreshDisplay(eslId, groupId)
        }
    }

    private suspend fun sendApAwareDisplay(eslId: Int, block: suspend () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is EslRpcException &&
                t.code == EslErrors.TAG_NOT_FOUND &&
                eslId == ESL_AP_LOCAL_ID
            ) {
                throw IllegalStateException(appContext.getString(R.string.msg_ap_display_unsupported))
            }
            throw t
        }
    }

    fun unassociateSelected() = runOnSelected { tag ->
        repo.rpc.unassociateTag(tag.eslId, tag.groupId)
        _message.value = UiMessage.res(R.string.msg_unassociate_started, tag.eslId)
    }

    fun setImageSlot(imageIndex: Int) {
        _imageEdit.value = _imageEdit.value.copy(imageIndex = imageIndex.coerceIn(0, 3))
    }

    fun setImageScaleMode(mode: EpaperScaleMode) {
        _imageEdit.value = _imageEdit.value.copy(scaleModeOrdinal = mode.ordinal)
        recomposeFromSource()
    }

    fun setImageThreshold(threshold: Int) {
        _imageEdit.value = _imageEdit.value.copy(threshold = threshold.coerceIn(0, 255))
        recomposeFromSource()
    }

    fun setImageDither(dither: Boolean) {
        _imageEdit.value = _imageEdit.value.copy(dither = dither)
        recomposeFromSource()
    }

    fun setImageInvert(invert: Boolean) {
        _imageEdit.value = _imageEdit.value.copy(invert = invert)
        recomposeFromSource()
    }

    /**
     * Photo Picker grant is only reliable at the Activity result callback.
     * UI must open the input stream there and pass it here immediately.
     */
    fun onImagePicked(uriString: String?, stream: InputStream?) {
        if (uriString == null || stream == null) {
            stream?.close()
            return
        }
        composeJob?.cancel()
        composeJob = viewModelScope.launch {
            _imageEdit.value = _imageEdit.value.copy(
                sourceUri = uriString,
                converting = true,
                error = null,
            )
            try {
                val bmp = stream.use { input ->
                    withContext(Dispatchers.IO) {
                        ImageBitmapLoader.load(input.readBytes())
                    }
                }
                sourceBitmap?.recycle()
                sourceBitmap = bmp
                runComposeLocked(bmp)
            } catch (t: Throwable) {
                runCatching { stream.close() }
                if (t is kotlinx.coroutines.CancellationException) throw t
                clearComposeOutputs()
                _imageEdit.value = _imageEdit.value.copy(
                    converting = false,
                    error = t.message ?: t::class.java.simpleName,
                )
            }
        }
    }

    fun onImagePickFailed(message: String) {
        composeJob?.cancel()
        clearComposeOutputs()
        _imageEdit.value = _imageEdit.value.copy(
            converting = false,
            error = message,
        )
    }

    fun clearPickedImage() {
        composeJob?.cancel()
        sourceBitmap?.recycle()
        sourceBitmap = null
        clearComposeOutputs()
        _imageEdit.value = ImageEditState(imageIndex = _imageEdit.value.imageIndex)
    }

    fun setBadgeSlot(slotIndex: Int) {
        _badgeEdit.value = _badgeEdit.value.copy(slotIndex = slotIndex.coerceIn(0, 3))
    }

    fun onBadgeImagePicked(uriString: String?, stream: InputStream?) {
        if (uriString == null || stream == null) {
            stream?.close()
            return
        }
        badgeComposeJob?.cancel()
        badgeComposeJob = viewModelScope.launch {
            _badgeEdit.value = _badgeEdit.value.copy(
                sourceUri = uriString,
                converting = true,
                error = null,
            )
            try {
                val bmp = stream.use { input ->
                    withContext(Dispatchers.IO) {
                        ImageBitmapLoader.load(input.readBytes())
                    }
                }
                badgeSourceBitmap?.recycle()
                badgeSourceBitmap = bmp
                val result = withContext(Dispatchers.Default) {
                    BadgeImageEncoder.compose(bmp)
                }
                val oldPreview = _badgeEdit.value.preview
                _badgeEdit.value = _badgeEdit.value.copy(
                    preview = result.preview,
                    raw = result.raw,
                    converting = false,
                    error = null,
                )
                if (oldPreview != null && oldPreview !== result.preview) oldPreview.recycle()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _badgeEdit.value.preview?.recycle()
                _badgeEdit.value = _badgeEdit.value.copy(
                    preview = null,
                    raw = null,
                    converting = false,
                    error = t.message ?: t::class.java.simpleName,
                )
            }
        }
    }

    fun onBadgeImagePickFailed(message: String) {
        badgeComposeJob?.cancel()
        _badgeEdit.value.preview?.recycle()
        _badgeEdit.value = _badgeEdit.value.copy(
            preview = null,
            raw = null,
            converting = false,
            error = message,
        )
    }

    fun clearBadgeImage() {
        badgeComposeJob?.cancel()
        badgeSourceBitmap?.recycle()
        badgeSourceBitmap = null
        _badgeEdit.value.preview?.recycle()
        _badgeEdit.value = BadgeEditState(slotIndex = _badgeEdit.value.slotIndex)
    }

    fun uploadBadgeToAp() = launchBusy(R.string.busy_badge_ap) {
        if (!supportsApBadgeImage) {
            throw IllegalStateException(appContext.getString(R.string.badge_err_unsupported_ap))
        }
        val prepared = prepareBadgeUpload(ImageUploadIds.DEST_AP)
        repo.rpc.transferBadgeImageToTag(
            ESL_AP_LOCAL_ID,
            0,
            prepared.imageId,
            prepared.slot,
            local = true,
        )
    }

    fun uploadBadgeAndTransfer() = runOnSelected { tag ->
        if (!tag.supportsBadgeImage) {
            throw IllegalStateException(appContext.getString(R.string.badge_err_unsupported_tag))
        }
        val prepared = prepareBadgeUpload(ImageUploadIds.DEST_TAG)
        repo.rpc.transferBadgeImageToTag(tag.eslId, tag.groupId, prepared.imageId, prepared.slot)
    }

    private data class PreparedBadgeUpload(val imageId: Long, val slot: Int)

    private suspend fun prepareBadgeUpload(dest: String): PreparedBadgeUpload {
        require(dest == ImageUploadIds.DEST_AP || dest == ImageUploadIds.DEST_TAG) {
            "badge dest must be ap or tag: $dest"
        }
        val state = _badgeEdit.value
        val bytes = state.raw
            ?: throw IllegalStateException(appContext.getString(R.string.badge_err_no_frame))
        if (bytes.size != BadgeImageEncoder.FRAME_BYTES) {
            throw IllegalStateException(appContext.getString(R.string.image_err_bad_size))
        }
        val slot = state.slotIndex.coerceIn(0, ImageUploadIds.MAX_IMAGE_INDEX)
        val imageId = if (dest == ImageUploadIds.DEST_TAG) {
            ImageUploadIds.badgeTagImageId(slot).toLong()
        } else {
            ImageUploadIds.badgeImageId(slot).toLong()
        }
        val maxChunk = helloInfo.value?.maxChunk?.coerceIn(64, 512) ?: 192
        repo.rpc.uploadBadgeImage(
            imageId = imageId,
            slotIndex = slot,
            dest = dest,
            bytes = bytes,
            crc32Hex = BinaryImageEncoder.crc32Hex(bytes),
            chunkSize = maxChunk,
        )
        return PreparedBadgeUpload(imageId, slot)
    }

    fun uploadDemoAndTransfer(imageIndex: Int = _imageEdit.value.imageIndex) = runOnSelected { tag ->
        val bytes = BinaryImageEncoder.demoCheckerboard()
        uploadFrameAndTransfer(tag, bytes, imageIndex)
    }

    fun uploadCustomAndTransfer() = runOnSelected { tag ->
        val state = _imageEdit.value
        val bytes = state.raw
            ?: throw IllegalStateException(appContext.getString(R.string.image_err_no_frame))
        if (bytes.size != BinaryImageEncoder.FRAME_BYTES) {
            throw IllegalStateException(appContext.getString(R.string.image_err_bad_size))
        }
        uploadFrameAndTransfer(tag, bytes, state.imageIndex)
    }

    private suspend fun uploadFrameAndTransfer(tag: TagRecord, bytes: ByteArray, imageIndex: Int) {
        val slot = imageIndex.coerceIn(0, ImageUploadIds.MAX_IMAGE_INDEX)
        val imageId = ImageUploadIds.customImageId(slot).toLong()
        val checksum = BinaryImageEncoder.crc32Hex(bytes)
        val version = ImageUploadIds.versionFromCrc32Hex(checksum).toLong()
        ImageUploadIds.assertInt32Positive(imageId, "image_id")
        ImageUploadIds.assertInt32Positive(version, "version")
        val maxChunk = helloInfo.value?.maxChunk?.coerceIn(64, 512) ?: 192
        repo.rpc.uploadImage(
            imageId = imageId,
            version = version,
            bytes = bytes,
            checksumHex = checksum,
            imageIndex = slot,
            chunkSize = maxChunk,
        )
        // Same image_index for diagnostics; AP OTS slot comes from upload_image_end store.
        repo.rpc.transferImageToTag(tag.eslId, tag.groupId, imageId, slot)
    }

    private fun recomposeFromSource() {
        val bmp = sourceBitmap ?: return
        composeJob?.cancel()
        composeJob = viewModelScope.launch {
            _imageEdit.value = _imageEdit.value.copy(converting = true, error = null)
            try {
                runComposeLocked(bmp)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                clearComposeOutputs()
                _imageEdit.value = _imageEdit.value.copy(
                    converting = false,
                    error = t.message ?: t::class.java.simpleName,
                )
            }
        }
    }

    private suspend fun runComposeLocked(bmp: Bitmap) {
        val opts = currentComposeOptions()
        val result = withContext(Dispatchers.Default) {
            EpaperFrameComposer.compose(bmp, opts)
        }
        val oldPreview = _imageEdit.value.preview
        _imageEdit.value = _imageEdit.value.copy(
            preview = result.preview,
            raw = result.raw,
            converting = false,
            error = null,
        )
        if (oldPreview != null && oldPreview !== result.preview) {
            oldPreview.recycle()
        }
    }

    private fun currentComposeOptions(): EpaperComposeOptions {
        val s = _imageEdit.value
        val mode = EpaperScaleMode.entries.getOrElse(s.scaleModeOrdinal) { EpaperScaleMode.CenterCrop }
        return EpaperComposeOptions(
            scaleMode = mode,
            threshold = s.threshold,
            dither = s.dither,
            invert = s.invert,
        )
    }

    private fun clearComposeOutputs() {
        _imageEdit.value.preview?.recycle()
        _imageEdit.value = _imageEdit.value.copy(preview = null, raw = null)
    }

    private fun runOnSelected(block: suspend (TagRecord) -> Unit) {
        if (_selectedTarget.value is ControlTarget.Ap) {
            _message.value = UiMessage.res(R.string.msg_ap_not_a_tag, isError = true)
            return
        }
        val tag = selectedTag
        if (tag == null) {
            _message.value = UiMessage.res(R.string.msg_select_tag, isError = true)
            return
        }
        launchBusyLabel(tag.bleAddr) { block(tag) }
    }

    /**
     * display_image / refresh_display: AP → 254; selected Tag → unicast;
     * group → only 255. Group display does not require an online Tag.
     */
    private fun runOnDisplayTarget(block: suspend (eslId: Int, groupId: Int, label: String) -> Unit) {
        when (
            val resolved = ControlTargetResolver.resolveDisplay(
                _selectedTarget.value,
                _controlScope.value,
                supportsGroupBroadcast,
            )
        ) {
            is ControlResolveResult.Fail -> {
                _message.value = UiMessage.res(R.string.msg_select_tag, isError = true)
            }
            is ControlResolveResult.Ok -> {
                launchBusyLabel(dispatchLabel(resolved.eslId, resolved.groupId)) {
                    block(resolved.eslId, resolved.groupId, dispatchLabel(resolved.eslId, resolved.groupId))
                }
            }
        }
    }

    /**
     * Ping / LED honor [controlScope] for real Tags only.
     * Group still requires an online peer. AP is not a PAwR target.
     */
    private fun runOnPawrTarget(block: suspend (eslId: Int, groupId: Int, label: String) -> Unit) {
        when (
            val resolved = ControlTargetResolver.resolveTagPawr(
                _selectedTarget.value,
                _controlScope.value,
                tags.value,
                supportsGroupBroadcast,
                ::groupHasOnlineTag,
            )
        ) {
            is ControlResolveResult.Fail -> {
                _message.value = when (resolved.reason) {
                    ControlResolveFail.ApNotAllowed -> UiMessage.res(R.string.msg_ap_pawr_unsupported, isError = true)
                    ControlResolveFail.GroupNotReady -> {
                        val groupId = (_selectedTarget.value as? ControlTarget.Tag)?.groupId ?: 0
                        UiMessage.res(R.string.msg_group_not_ready, groupId, isError = true)
                    }
                    ControlResolveFail.NoTarget, ControlResolveFail.TagGone ->
                        UiMessage.res(R.string.msg_select_tag, isError = true)
                }
            }
            is ControlResolveResult.Ok -> {
                val label = dispatchLabel(resolved.eslId, resolved.groupId)
                launchBusyLabel(label) {
                    block(resolved.eslId, resolved.groupId, label)
                }
            }
        }
    }

    private fun dispatchLabel(eslId: Int, groupId: Int): String {
        return when (eslId) {
            ESL_AP_LOCAL_ID -> appContext.getString(R.string.controls_ap_label)
            ESL_GROUP_BROADCAST_ID -> appContext.getString(R.string.controls_group_label, groupId)
            else -> selectedTag?.bleAddr ?: "esl_id=$eslId"
        }
    }

    private fun launchBusy(labelRes: Int, vararg labelArgs: Any, block: suspend () -> Unit) {
        val label = appContext.getString(labelRes, *labelArgs)
        launchBusyLabel(label, block)
    }

    private fun launchBusyLabel(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            if (_busy.value) {
                _message.value = UiMessage.res(R.string.msg_busy, label, isError = true)
                return@launch
            }
            _busy.value = true
            try {
                block()
                _message.value = UiMessage.res(R.string.msg_done, label)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _message.value = UiMessage.raw(t.message ?: t::class.java.simpleName, isError = true)
            } finally {
                withContext(NonCancellable) {
                    _busy.value = false
                }
            }
        }
    }

    override fun onCleared() {
        composeJob?.cancel()
        sourceBitmap?.recycle()
        sourceBitmap = null
        _imageEdit.value.preview?.recycle()
        repo.stop()
        super.onCleared()
    }
}
