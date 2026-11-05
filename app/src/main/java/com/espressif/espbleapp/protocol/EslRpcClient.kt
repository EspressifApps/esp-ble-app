package com.espressif.espbleapp.protocol

import com.espressif.espbleapp.data.ApStatus
import com.espressif.espbleapp.data.HelloInfo
import com.espressif.espbleapp.data.LogLine
import com.espressif.espbleapp.data.OrphanBond
import com.espressif.espbleapp.data.ScannedTag
import com.espressif.espbleapp.data.TagRecord
import com.espressif.espbleapp.data.TaskKind
import com.espressif.espbleapp.data.TaskRecord
import com.espressif.espbleapp.data.TaskStatus
import com.espressif.espbleapp.transport.ApConnectionState
import com.espressif.espbleapp.transport.ApTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
class EslRpcException(val code: String, message: String) : Exception(message)

class EslRpcClient(
    private val transport: ApTransport,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val flightMutex = Mutex()
    private var pendingId: String? = null
    private var pendingCont: Continuation<IncomingMessage.RpcResponse>? = null
    private val seq = AtomicLong(1)

    private class CommandWaiter(
        val cmd: String,
        val eslId: Int,
        val result: CompletableDeferred<String>,
    )

    /** Resolved on the transport collector thread, registered on the caller's. */
    private val commandWaiters = CopyOnWriteArrayList<CommandWaiter>()

    private val _hello = MutableStateFlow<HelloInfo?>(null)
    private val _apStatus = MutableStateFlow(ApStatus())
    private val _tags = MutableStateFlow<List<TagRecord>>(emptyList())
    private val _scannedTags = MutableStateFlow<List<ScannedTag>>(emptyList())
    private val _selectedIndex = MutableStateFlow(0)
    private val _tasks = MutableStateFlow<List<TaskRecord>>(emptyList())
    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    private val _events = MutableSharedFlow<IncomingMessage.Event>(extraBufferCapacity = 64)

    val helloInfo: StateFlow<HelloInfo?> = _hello.asStateFlow()
    val apStatus: StateFlow<ApStatus> = _apStatus.asStateFlow()
    val tags: StateFlow<List<TagRecord>> = _tags.asStateFlow()
    val scannedTags: StateFlow<List<ScannedTag>> = _scannedTags.asStateFlow()
    val selectedIndex: StateFlow<Int> = _selectedIndex.asStateFlow()
    val tasks: StateFlow<List<TaskRecord>> = _tasks.asStateFlow()
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()
    val events: SharedFlow<IncomingMessage.Event> = _events.asSharedFlow()
    val connectionState: StateFlow<ApConnectionState> = transport.connectionState

    private var collectorJob: Job? = null
    private var connectionJob: Job? = null

    fun start() {
        if (collectorJob != null) return
        collectorJob = scope.launch {
            transport.incomingLines.collect { line ->
                handleIncoming(line)
            }
        }
        connectionJob = scope.launch {
            var wasReady = false
            transport.connectionState.collect { state ->
                if (state == ApConnectionState.Ready && !wasReady) {
                    wasReady = true
                    launch { onTransportReady() }
                } else if (state != ApConnectionState.Ready) {
                    wasReady = false
                    if (state == ApConnectionState.Disconnected || state == ApConnectionState.Idle) {
                        clearScannedTags()
                        _hello.value = null
                    }
                }
            }
        }
    }

    /** Hello only refreshes status; ESL/PAwR recovery starts independently on the AP. */
    private suspend fun onTransportReady() {
        try {
            hello()
            getApStatus()
            listTags()
            appendLog("I", "transport ready: hello + status refreshed")
        } catch (t: Throwable) {
            appendLog("E", "transport ready failed: ${t.message}")
        }
    }

    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
        connectionJob?.cancel()
        connectionJob = null
        clearPending(EslRpcException(EslErrors.NOT_CONNECTED, "stopped"))
    }

    private fun clearScannedTags() {
        _scannedTags.value = emptyList()
    }

    private fun upsertScannedTag(tag: ScannedTag) {
        val now = System.currentTimeMillis()
        val fresh = _scannedTags.value.filter { now - it.lastSeenMs < SCANNED_TAG_MAX_AGE_MS }
        val updated = fresh
            .filterNot { it.bleAddr.equals(tag.bleAddr, ignoreCase = true) } + tag
        _scannedTags.value = updated.sortedByDescending { it.rssi }
    }

    suspend fun hello(
        client: String = "esl_ap_controller",
        clientVer: String = "0.1.0",
    ): HelloInfo {
        val data = request(
            op = "hello",
            data = buildJsonObject {
                put("client", client)
                put("client_ver", clientVer)
            },
        )
        val info = HelloInfo(
            protocol = data.string("protocol"),
            fwVer = data.string("fw_ver"),
            deviceName = data.string("device_name"),
            maxChunk = data.int("max_chunk", 256),
            maxLine = data.int("max_line", 512),
            encrypted = data.bool("encrypted"),
            capabilities = data["capabilities"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty(),
        )
        _hello.value = info
        return info
    }

    suspend fun transportPing(nonce: String = "ping"): JsonObject =
        request(op = "ping", data = buildJsonObject { put("nonce", nonce) })

    suspend fun getApStatus(): ApStatus {
        val data = esl(EslCommands.GET_AP_STATUS)
        val orphans = data["orphan_bonds"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el.jsonObject
            OrphanBond(
                addr = o.string("addr"),
                addrType = o.int("addr_type"),
                firstSeenUptimeMs = o.long("first_seen_uptime_ms"),
            )
        }
        val status = ApStatus(
            started = data.bool("started"),
            scanning = data.bool("scanning"),
            pawrActive = data.bool("pawr_active"),
            tagCount = data.int("tag_count"),
            selectedIndex = data.int("selected_index"),
            phoneLink = data.string("phone_link", "connected"),
            lastError = data["last_error"]?.let {
                if (it is JsonPrimitive && it.contentOrNull != null && it.content != "null") {
                    it.content
                } else {
                    null
                }
            },
            bootId = data.string("boot_id"),
            recoveryPhase = data.string("recovery_phase", "unknown"),
            health = data.string("health", "unknown"),
            retryable = data["retryable"]?.let { data.bool("retryable") },
            errorCode = data["error_code"]?.jsonPrimitive?.contentOrNull,
            retryCount = data["retry_count"]?.let { data.int("retry_count") },
            nextRetryUptimeMs = data["next_retry_uptime_ms"]?.let { data.long("next_retry_uptime_ms") },
            recommendedAction = data["recommended_action"]?.jsonPrimitive?.contentOrNull,
            orphanBonds = orphans,
        )
        _apStatus.value = status
        _selectedIndex.value = status.selectedIndex
        return status
    }

    suspend fun listTags(): List<TagRecord> {
        val paginate = _hello.value?.capabilities?.contains("list-tags-pagination") == true
        val all = mutableListOf<TagRecord>()
        var offset = 0
        var selected = 0
        do {
            val data = if (paginate) {
                esl(EslCommands.LIST_TAGS) {
                    put("offset", JsonPrimitive(offset))
                    put("limit", JsonPrimitive(PAGE_LIMIT))
                }
            } else {
                esl(EslCommands.LIST_TAGS)
            }
            selected = data.int("selected_index", selected)
            val page = data["tags"]?.jsonArray.orEmpty().mapNotNull { el ->
                parseTagRecord(el.jsonObject)
            }
            all += page
            if (!paginate) {
                break
            }
            val next = data.int("next_offset", offset + page.size)
            val total = data.int("total", all.size)
            if (next <= offset || next >= total || page.isEmpty()) {
                break
            }
            offset = next
        } while (true)
        _selectedIndex.value = selected
        _tags.value = all
        return all
    }

    suspend fun cancelProvision(eslId: Int, groupId: Int): JsonObject =
        esl(EslCommands.CANCEL_PROVISION) {
            put("tag", EspJsonlRpc.tagSelector(eslId, groupId))
        }

    suspend fun scanStart(): TaskRecord {
        clearScannedTags()
        val task = createTask(TaskKind.Scan, "Scan start")
        esl(EslCommands.SCAN_START)
        return updateTask(task.id) { it.copy(status = TaskStatus.Accepted, detail = "accepted") }
    }

    suspend fun scanStop(): TaskRecord {
        val task = createTask(TaskKind.Scan, "Scan stop")
        esl(EslCommands.SCAN_STOP)
        getApStatus()
        return updateTask(task.id) { it.copy(status = TaskStatus.Accepted, detail = "stopped") }
    }

    data class ProvisionResult(val eslId: Int, val groupId: Int)

    suspend fun provisionTag(
        bleAddr: String,
        addrType: Int = 0,
        eslId: Int? = null,
        groupId: Int? = null,
    ): ProvisionResult {
        val task = createTask(TaskKind.Other, "Provision $bleAddr")
        val data = esl(EslCommands.PROVISION_TAG) {
            put(
                "tag",
                buildJsonObject {
                    put("ble_addr", bleAddr)
                    put("addr_type", addrType)
                },
            )
            if (eslId != null) put("esl_id", JsonPrimitive(eslId))
            if (groupId != null) put("group_id", JsonPrimitive(groupId))
        }
        val assignedEslId = data.int("esl_id", eslId ?: 0)
        val assignedGroupId = data.int("group_id", groupId ?: 0)
        updateTask(task.id) {
            it.copy(
                status = TaskStatus.Accepted,
                detail = "esl_id=$assignedEslId",
                tagEslId = assignedEslId,
                tagGroupId = assignedGroupId,
            )
        }
        return ProvisionResult(assignedEslId, assignedGroupId)
    }

    suspend fun pingTag(eslId: Int, groupId: Int): TaskRecord =
        acceptedCommand(TaskKind.Ping, "Ping tag $eslId", EslCommands.PING, eslId, groupId)

    suspend fun unassociateTag(eslId: Int, groupId: Int): TaskRecord =
        acceptedCommand(
            kind = TaskKind.Unassociate,
            label = "Unassociate tag $eslId",
            cmd = EslCommands.UNASSOCIATE,
            eslId = eslId,
            groupId = groupId,
        )

    suspend fun ledControl(
        eslId: Int,
        groupId: Int,
        ledIndex: Int = 0,
        colorRed: Int = 3,
        colorGreen: Int = 0,
        colorBlue: Int = 0,
        brightness: Int = 3,
        repeatType: Int = 1,
        repeatsDuration: Int = 0,
        patternHex: String? = null,
        bitOffPeriod: Int? = null,
        bitOnPeriod: Int? = null,
    ): TaskRecord =
        acceptedCommand(
            kind = TaskKind.LedControl,
            label = "LED tag $eslId",
            cmd = EslCommands.LED_CONTROL,
            eslId = eslId,
            groupId = groupId,
        ) {
            put("led_index", JsonPrimitive(ledIndex))
            put("color_red", JsonPrimitive(colorRed))
            put("color_green", JsonPrimitive(colorGreen))
            put("color_blue", JsonPrimitive(colorBlue))
            put("brightness", JsonPrimitive(brightness))
            put("repeat_type", JsonPrimitive(repeatType))
            put("repeats_duration", JsonPrimitive(repeatsDuration))
            if (patternHex != null) {
                put("pattern", JsonPrimitive(patternHex))
            }
            if (bitOffPeriod != null) {
                put("bit_off_period", JsonPrimitive(bitOffPeriod))
            }
            if (bitOnPeriod != null) {
                put("bit_on_period", JsonPrimitive(bitOnPeriod))
            }
        }

    suspend fun displayImage(
        eslId: Int,
        groupId: Int,
        displayIndex: Int = 0,
        imageIndex: Int = 0,
    ): TaskRecord =
        acceptedCommand(
            kind = TaskKind.DisplayImage,
            label = "Display img=$imageIndex",
            cmd = EslCommands.DISPLAY_IMAGE,
            eslId = eslId,
            groupId = groupId,
        ) {
            put("display_index", JsonPrimitive(displayIndex))
            put("image_index", JsonPrimitive(imageIndex))
        }

    /**
     * Send display_image and suspend until the AP reports command_result for the
     * same tag. The AP runs a single PAwR command at a time and answers
     * overlapping ones with "busy", so batch callers must chain on completion
     * rather than on the "accepted" reply. Returns the reported status.
     */
    suspend fun displayImageAwait(
        eslId: Int,
        groupId: Int,
        displayIndex: Int = 0,
        imageIndex: Int = 0,
        timeoutMs: Long = COMMAND_RESULT_TIMEOUT_MS,
    ): String =
        awaitCommandResult(EslCommands.DISPLAY_IMAGE, eslId, timeoutMs) {
            displayImage(eslId, groupId, displayIndex, imageIndex)
        }

    /**
     * Register the waiter before [send] runs so a command_result that arrives
     * while the accept reply is still in flight cannot be missed.
     */
    private suspend fun awaitCommandResult(
        cmd: String,
        eslId: Int,
        timeoutMs: Long,
        send: suspend () -> Unit,
    ): String {
        val waiter = CommandWaiter(cmd, eslId, CompletableDeferred())
        commandWaiters.add(waiter)
        try {
            send()
            return withTimeout(timeoutMs) { waiter.result.await() }
        } catch (_: TimeoutCancellationException) {
            throw EslRpcException(EslErrors.TIMEOUT, "no command_result for $cmd esl_id=$eslId")
        } finally {
            commandWaiters.remove(waiter)
        }
    }

    private fun completeCommandWaiters(cmd: String, eslId: Int?, status: String) {
        for (waiter in commandWaiters) {
            if (waiter.cmd == cmd && (eslId == null || waiter.eslId == eslId)) {
                waiter.result.complete(status)
            }
        }
    }

    suspend fun refreshDisplay(eslId: Int, groupId: Int, displayIndex: Int = 0): TaskRecord =
        acceptedCommand(
            kind = TaskKind.RefreshDisplay,
            label = "Refresh display",
            cmd = EslCommands.REFRESH_DISPLAY,
            eslId = eslId,
            groupId = groupId,
        ) {
            put("display_index", JsonPrimitive(displayIndex))
        }

    suspend fun uploadImage(
        imageId: Long,
        version: Long,
        bytes: ByteArray,
        checksumHex: String,
        imageIndex: Int = 0,
        chunkSize: Int = 192,
        format: String = "raw",
    ) {
        require(imageId in 1..Int.MAX_VALUE) { "image_id out of AP int32 range: $imageId" }
        require(version in 1..Int.MAX_VALUE) { "version out of AP int32 range: $version" }
        require(imageIndex in 0..3) { "image_index out of range: $imageIndex" }
        val task = createTask(TaskKind.UploadImage, "Upload image $imageId slot=$imageIndex")
        try {
            esl(EslCommands.UPLOAD_IMAGE_BEGIN) {
                put("image_id", JsonPrimitive(imageId))
                put("version", JsonPrimitive(version))
                put("data_len", JsonPrimitive(bytes.size))
                put("checksum", JsonPrimitive(checksumHex))
                put("format", JsonPrimitive(format))
            }
            sendUploadPayload(
                bytes = bytes,
                chunkSize = chunkSize,
                onProgress = { offset ->
                    updateTask(task.id) {
                        it.copy(
                            status = TaskStatus.Running,
                            detail = "uploaded $offset/${bytes.size}",
                        )
                    }
                },
            ) { offset, chunk ->
                val b64 = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)
                esl(EslCommands.UPLOAD_IMAGE_CHUNK) {
                    put("image_id", JsonPrimitive(imageId))
                    put("version", JsonPrimitive(version))
                    put("offset", JsonPrimitive(offset))
                    put("data", JsonPrimitive(b64))
                }
            }
            // AP store_runtime uses upload_image_end.image_index as the authoritative Tag slot.
            esl(EslCommands.UPLOAD_IMAGE_END) {
                put("image_id", JsonPrimitive(imageId))
                put("version", JsonPrimitive(version))
                put("image_index", JsonPrimitive(imageIndex))
            }
            updateTask(task.id) { it.copy(status = TaskStatus.Ok, detail = "stored slot=$imageIndex") }
        } catch (t: Throwable) {
            updateTask(task.id) {
                it.copy(status = TaskStatus.Error, detail = t.message ?: "upload failed")
            }
            throw t
        }
    }

    suspend fun transferImageToTag(
        eslId: Int,
        groupId: Int,
        imageId: Long,
        imageIndex: Int = 0,
    ): TaskRecord =
        acceptedCommand(
            kind = TaskKind.TransferImage,
            label = "OTS transfer image_id=$imageId",
            cmd = EslCommands.TRANSFER_IMAGE_TO_TAG,
            eslId = eslId,
            groupId = groupId,
        ) {
            put("image_id", JsonPrimitive(imageId))
            put("image_index", JsonPrimitive(imageIndex))
        }

    suspend fun uploadBadgeImage(
        imageId: Long,
        slotIndex: Int,
        dest: String,
        bytes: ByteArray,
        crc32Hex: String,
        chunkSize: Int = 192,
    ) {
        require(imageId in 1..Int.MAX_VALUE) { "image_id out of AP int32 range: $imageId" }
        require(slotIndex in 0..3) { "slot_index out of range: $slotIndex" }
        require(dest == "ap" || dest == "tag") { "dest must be ap or tag: $dest" }
        require(bytes.size == 480 * 480 * 2) { "badge image must be 460800 bytes" }
        val task = createTask(TaskKind.UploadImage, "Upload badge dest=$dest image $imageId slot=$slotIndex")
        try {
            esl(EslCommands.UPLOAD_BADGE_IMAGE_BEGIN) {
                put("image_id", JsonPrimitive(imageId))
                put("slot_index", JsonPrimitive(slotIndex))
                put("dest", JsonPrimitive(dest))
                put("data_len", JsonPrimitive(bytes.size))
                put("crc32", JsonPrimitive(crc32Hex))
                put("width", JsonPrimitive(480))
                put("height", JsonPrimitive(480))
            }
            sendUploadPayload(
                bytes = bytes,
                chunkSize = chunkSize,
                onProgress = { offset ->
                    updateTask(task.id) {
                        it.copy(status = TaskStatus.Running, detail = "uploaded $offset/${bytes.size}")
                    }
                },
            ) { offset, chunk ->
                val b64 = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)
                esl(EslCommands.UPLOAD_BADGE_IMAGE_CHUNK) {
                    put("image_id", JsonPrimitive(imageId))
                    put("offset", JsonPrimitive(offset))
                    put("data_b64", JsonPrimitive(b64))
                }
            }
            esl(EslCommands.UPLOAD_BADGE_IMAGE_END) {
                put("image_id", JsonPrimitive(imageId))
            }
            updateTask(task.id) { it.copy(status = TaskStatus.Ok, detail = "stored slot=$slotIndex") }
        } catch (t: Throwable) {
            updateTask(task.id) {
                it.copy(status = TaskStatus.Error, detail = t.message ?: "badge upload failed")
            }
            throw t
        }
    }

    suspend fun transferBadgeImageToTag(
        eslId: Int,
        groupId: Int,
        imageId: Long,
        slotIndex: Int = 0,
        local: Boolean = false,
    ): TaskRecord =
        acceptedCommand(
            kind = TaskKind.TransferImage,
            label = "Transfer badge image_id=$imageId",
            cmd = EslCommands.TRANSFER_BADGE_IMAGE_TO_TAG,
            eslId = eslId,
            groupId = groupId,
        ) {
            put("image_id", JsonPrimitive(imageId))
            put("slot_index", JsonPrimitive(slotIndex))
            if (local) put("local", JsonPrimitive(true))
        }

    suspend fun listBadgeImages(): JsonObject = esl(EslCommands.LIST_BADGE_IMAGES)

    private suspend fun acceptedCommand(
        kind: TaskKind,
        label: String,
        cmd: String,
        eslId: Int,
        groupId: Int,
        extra: MutableMap<String, kotlinx.serialization.json.JsonElement>.() -> Unit = {},
    ): TaskRecord {
        val task = createTask(kind, label, eslId, groupId)
        esl(cmd) {
            put("tag", EspJsonlRpc.tagSelector(eslId, groupId))
            extra()
        }
        return updateTask(task.id) { it.copy(status = TaskStatus.Accepted, detail = "accepted") }
    }

    private suspend fun sendUploadPayload(
        bytes: ByteArray,
        chunkSize: Int,
        onProgress: (Int) -> Unit,
        sendChunk: suspend (offset: Int, chunk: ByteArray) -> Unit,
    ) {
        if (_hello.value?.supportsRawUpload == true) {
            onProgress(0)
            transport.sendBytes(bytes, onProgress)
            onProgress(bytes.size)
            return
        }
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            sendChunk(offset, bytes.copyOfRange(offset, end))
            offset = end
            onProgress(offset)
        }
    }

    private suspend fun esl(
        cmd: String,
        builder: MutableMap<String, kotlinx.serialization.json.JsonElement>.() -> Unit = {},
    ): JsonObject {
        val map = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "cmd" to JsonPrimitive(cmd),
        )
        map.builder()
        return request(op = "esl", data = JsonObject(map))
    }

    private suspend fun request(
        op: String,
        data: JsonObject,
        timeoutMs: Long = 5_000,
    ): JsonObject {
        val id = "req-${seq.getAndIncrement()}"
        val req = RpcRequest(id = id, op = op, data = data)
        val response: IncomingMessage.RpcResponse = flightMutex.withLock {
            if (pendingId != null) {
                throw EslRpcException(EslErrors.BUSY, "single-flight busy")
            }
            try {
                withTimeout(timeoutMs) {
                    coroutineScope {
                        val waiter = async {
                            suspendCoroutine<IncomingMessage.RpcResponse> { cont ->
                                pendingId = id
                                pendingCont = cont
                            }
                        }
                        appendLog("D", "rpc send op=$op id=$id")
                        try {
                            withContext(Dispatchers.Main.immediate) {
                                transport.sendLine(req.toJsonLine())
                            }
                        } catch (t: Throwable) {
                            clearPending(t)
                            throw t
                        }
                        waiter.await()
                    }
                }
            } catch (_: TimeoutCancellationException) {
                clearPending(EslRpcException(EslErrors.TIMEOUT, "sync timeout"))
                throw EslRpcException(EslErrors.TIMEOUT, "sync timeout for $id")
            }
        }
        appendLog("D", "rpc ok op=$op id=$id")
        if (!response.ok) {
            throw EslRpcException(response.error ?: EslErrors.INTERNAL, response.error ?: "rpc failed")
        }
        return response.data
    }

    private fun handleIncoming(line: String) {
        val msg = try {
            EspJsonlRpc.parseIncoming(line)
        } catch (t: Throwable) {
            appendLog("E", "parse failed: ${t.message} line=${line.take(120)}")
            return
        }
        when (msg) {
            is IncomingMessage.RpcResponse -> {
                val cont = pendingCont
                if (pendingId == msg.id && cont != null) {
                    pendingId = null
                    pendingCont = null
                    appendLog("D", "rpc rx id=${msg.id} ok=${msg.ok}")
                    cont.resume(msg)
                } else {
                    appendLog("W", "unmatched rpc response id=${msg.id}")
                }
            }
            is IncomingMessage.Event -> {
                scope.launch { _events.emit(msg) }
                handleEvent(msg)
            }
            is IncomingMessage.Unsolicited -> appendLog("D", "unsolicited: ${msg.raw}")
        }
    }

    private fun handleEvent(event: IncomingMessage.Event) {
        when (event.name) {
            EslEvents.LOG -> appendLog(
                event.raw.string("level", "I"),
                event.raw.string("msg", event.raw.toString()),
            )
            EslEvents.COMMAND_RESULT -> {
                val cmd = event.raw.string("cmd")
                val status = event.raw.string("status")
                val tag = event.raw.obj("tag")
                val eslId = tag?.int("esl_id")
                val apDisplayed = event.raw.boolOrNull("ap_displayed")
                val detail = if (apDisplayed == null) {
                    status
                } else {
                    "$status ap_displayed=$apDisplayed"
                }
                updateLatestMatching(cmd, eslId) { task ->
                    task.copy(
                        status = when (status) {
                            "ok" -> TaskStatus.Ok
                            "timeout" -> TaskStatus.Timeout
                            else -> TaskStatus.Error
                        },
                        detail = detail,
                        updatedAtMs = System.currentTimeMillis(),
                    )
                }
                appendLog("I", "command_result cmd=$cmd status=$status ap_displayed=$apDisplayed")
                completeCommandWaiters(cmd, eslId, status)
                if (cmd == EslCommands.UNASSOCIATE) {
                    scope.launch {
                        try {
                            listTags()
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
            EslEvents.IMAGE_TRANSFER_PROGRESS -> {
                val offset = event.raw.int("offset")
                val total = event.raw.int("total")
                updateLatestMatching(EslCommands.TRANSFER_IMAGE_TO_TAG, event.raw.int("esl_id")) {
                    it.copy(status = TaskStatus.Running, detail = "$offset/$total")
                }
            }
            EslEvents.IMAGE_UPLOADED -> appendLog("I", "image_uploaded ${event.raw}")
            EslEvents.BADGE_TRANSFER_PROGRESS -> {
                val offset = event.raw.int("offset")
                val total = event.raw.int("total")
                updateLatestMatching(EslCommands.TRANSFER_BADGE_IMAGE_TO_TAG, event.raw.int("esl_id")) {
                    it.copy(status = TaskStatus.Running, detail = "$offset/$total")
                }
            }
            EslEvents.BADGE_IMAGE_UPLOADED -> appendLog("I", "badge_image_uploaded ${event.raw}")
            EslEvents.SCAN_RESULT -> {
                val addr = event.raw.string("ble_addr")
                if (addr.isNotBlank()) {
                    upsertScannedTag(
                        ScannedTag(
                            bleAddr = addr,
                            addrType = event.raw.int("addr_type"),
                            rssi = event.raw.int("rssi"),
                            associated = event.raw.bool("associated"),
                            lastSeenMs = System.currentTimeMillis(),
                        ),
                    )
                }
                appendLog(
                    "I",
                    "scan $addr rssi=${event.raw.int("rssi")} associated=${event.raw.bool("associated")}",
                )
            }
            EslEvents.TAG_STATE_CHANGED -> {
                appendLog("I", "tag_state ${event.raw}")
                val state = event.raw.string("state")
                if (state == "synchronized" || state == "unassociated") {
                    scope.launch {
                        try {
                            listTags()
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
            EslEvents.PROVISION_FAILED -> appendLog(
                "W",
                "provision_failed ${event.raw.string("ble_addr")} ${event.raw.string("reason")}",
            )
            EslEvents.AP_STATUS_CHANGED -> appendLog("I", "ap_status_changed")
            EslEvents.RECOVERY_SUMMARY_CHANGED -> {
                appendLog(
                    "I",
                    "recovery_summary phase=${event.raw.string("recovery_phase")} health=${event.raw.string("health")}",
                )
                scope.launch {
                    try {
                        getApStatus()
                        listTags()
                    } catch (_: Throwable) {
                    }
                }
            }
            EslEvents.TAG_RECOVERY_CHANGED -> {
                appendLog("I", "tag_recovery_changed slot=${event.raw.int("slot")}")
                scope.launch {
                    try {
                        listTags()
                    } catch (_: Throwable) {
                    }
                }
            }
            else -> appendLog("D", "event ${event.name}")
        }
    }

    private fun parseTagRecord(o: JsonObject): TagRecord =
        TagRecord(
            bleAddr = o.string("ble_addr"),
            addrType = o.int("addr_type"),
            eslId = o.int("esl_id"),
            groupId = o.int("group_id"),
            eslAddr = o.int("esl_addr"),
            state = o.string("state", "unknown"),
            currentImageIndex = o.int("current_image_index"),
            hasDisplay = o.bool("has_display"),
            hasLed = o.bool("has_led"),
            hasSensor = o.bool("has_sensor"),
            numDisplays = o.int("num_displays"),
            maxImageIndex = o.int("max_image_index"),
            valid = o.bool("valid", true),
            productState = o.string("product_state", "unknown"),
            eslRuntimeState = o.string("esl_runtime_state", "unknown"),
            associated = o.bool("associated"),
            desiredState = o.string("desired_state", "unknown"),
            timeSynced = o.bool("time_synced"),
            recoveryGeneration = o.int("recovery_generation"),
            lastSeenUptimeMs = o.long("last_seen_uptime_ms"),
            retryCount = o.int("retry_count"),
            failureReason = o["failure_reason"]?.let {
                if (it is JsonPrimitive && it.contentOrNull != null && it.content != "null") {
                    it.content
                } else {
                    null
                }
            },
            provisionPhase = o.string("provision_phase", "unknown"),
            displayWidth = o["display_width"]?.let { o.int("display_width") },
            displayHeight = o["display_height"]?.let { o.int("display_height") },
            displayType = o["display_type"]?.let { o.int("display_type") },
        )

    private fun createTask(
        kind: TaskKind,
        label: String,
        eslId: Int? = null,
        groupId: Int? = null,
    ): TaskRecord {
        val task = TaskRecord(
            id = UUID.randomUUID().toString(),
            kind = kind,
            label = label,
            tagEslId = eslId,
            tagGroupId = groupId,
        )
        _tasks.value = listOf(task) + _tasks.value
        return task
    }

    private fun updateTask(id: String, transform: (TaskRecord) -> TaskRecord): TaskRecord {
        var updated: TaskRecord? = null
        _tasks.value = _tasks.value.map {
            if (it.id == id) {
                val t = transform(it).copy(updatedAtMs = System.currentTimeMillis())
                updated = t
                t
            } else {
                it
            }
        }
        return updated ?: throw IllegalStateException("task $id missing")
    }

    private fun updateLatestMatching(cmd: String, eslId: Int?, transform: (TaskRecord) -> TaskRecord) {
        val kind = when (cmd) {
            EslCommands.UNASSOCIATE -> TaskKind.Unassociate
            EslCommands.PING -> TaskKind.Ping
            EslCommands.LED_CONTROL -> TaskKind.LedControl
            EslCommands.DISPLAY_IMAGE -> TaskKind.DisplayImage
            EslCommands.REFRESH_DISPLAY -> TaskKind.RefreshDisplay
            EslCommands.TRANSFER_IMAGE_TO_TAG -> TaskKind.TransferImage
            EslCommands.SCAN_START -> TaskKind.Scan
            EslCommands.SCAN_STOP -> TaskKind.Scan
            else -> null
        }
        val idx = _tasks.value.indexOfFirst {
            (kind == null || it.kind == kind) &&
                (eslId == null || it.tagEslId == eslId) &&
                it.status in setOf(TaskStatus.Accepted, TaskStatus.Running, TaskStatus.Pending)
        }
        if (idx < 0) return
        _tasks.value = _tasks.value.toMutableList().also {
            it[idx] = transform(it[idx])
        }
    }

    private fun appendLog(level: String, message: String) {
        val line = LogLine(level = level, message = message)
        _logs.value = (listOf(line) + _logs.value).take(300)
    }

    private fun clearPending(t: Throwable) {
        pendingCont?.resumeWithException(t)
        pendingCont = null
        pendingId = null
    }

    companion object {
        /** Drop scan results not refreshed within this window. */
        const val SCANNED_TAG_MAX_AGE_MS = 60_000L
        /** Page size when AP advertises list-tags-pagination. */
        private const val PAGE_LIMIT = 2

        /**
         * A PAwR command reaches the tag on its subevent slot, so allow a few
         * PAwR periods plus the AP's own 30 s ECP timeout before giving up.
         */
        const val COMMAND_RESULT_TIMEOUT_MS = 35_000L
    }
}
