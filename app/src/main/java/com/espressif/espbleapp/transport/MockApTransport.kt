package com.espressif.espbleapp.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-memory AP that speaks docs/esl-ap-protocol.md v0.1 JSONL for UI/CI without hardware.
 */
class MockApTransport(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ApTransport {
    private val _connectionState = MutableStateFlow(ApConnectionState.Idle)
    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val _scanned = MutableStateFlow(
        listOf(
            BleDeviceInfo(name = "ESP-ESL-AP-MOCK", address = "AA:BB:CC:DD:EE:FF", rssi = -42),
        ),
    )
    private val writeMutex = Mutex()
    private val framer = JsonlFramer()

    private var selectedIndex = 0
    private val tags = mutableListOf(
        JSONObject()
            .put("ble_addr", "aa:bb:cc:dd:ee:01")
            .put("addr_type", 0)
            .put("esl_id", 0)
            .put("group_id", 0)
            .put("esl_addr", 0)
            .put("state", "synchronized")
            .put("current_image_index", 0)
            .put("has_display", true)
            .put("has_led", true)
            .put("has_sensor", true)
            .put("num_displays", 1)
            .put("max_image_index", 3)
            .put("display_width", 480)
            .put("display_height", 480)
            .put("display_type", 11)
            .put("valid", true)
            .put("product_state", "online")
            .put("esl_runtime_state", "synchronized")
            .put("associated", true)
            .put("desired_state", "active")
            .put("time_synced", true)
            .put("recovery_generation", 1)
            .put("last_seen_uptime_ms", 12000)
            .put("retry_count", 0)
            .put("failure_reason", JSONObject.NULL)
            .put("provision_phase", "active"),
        JSONObject()
            .put("ble_addr", "aa:bb:cc:dd:ee:02")
            .put("addr_type", 0)
            .put("esl_id", 1)
            .put("group_id", 0)
            .put("esl_addr", 1)
            .put("state", "unsynchronized")
            .put("current_image_index", 1)
            .put("has_display", true)
            .put("has_led", true)
            .put("has_sensor", false)
            .put("num_displays", 1)
            .put("max_image_index", 3)
            .put("display_width", 296)
            .put("display_height", 128)
            .put("display_type", 1)
            .put("valid", true)
            .put("product_state", "recovering")
            .put("esl_runtime_state", "unsynchronized")
            .put("associated", true)
            .put("desired_state", "active")
            .put("time_synced", false)
            .put("recovery_generation", 2)
            .put("last_seen_uptime_ms", 5000)
            .put("retry_count", 1)
            .put("failure_reason", "ping_timeout")
            .put("provision_phase", "active"),
    )

    private var uploadImageId: Int? = null
    private var uploadVersion: Int = 1
    private var uploadExpected = 0
    private var uploadReceived = 0
    private var uploadChecksum: String? = null
    /** Mirrors AP esl_image_manager 4 runtime slots keyed by image_id. */
    private val runtimeImages = LinkedHashMap<Int, RuntimeImage>()
    private var badgeUploadImageId: Int? = null
    private var badgeUploadSlot = 0
    private var badgeUploadDest = "ap"
    private var badgeUploadExpected = 0
    private var badgeUploadReceived = 0
    private var badgeUploadCrc32 = ""
    private val badgeImages = LinkedHashMap<Int, BadgeImage>()
    private val badgeForward = LinkedHashMap<Int, BadgeImage>()
    private var apCurrentSlot: Int? = null

    private data class RuntimeImage(
        val imageId: Int,
        val imageIndex: Int,
        val version: Int,
        val dataLen: Int,
    )

    private data class BadgeImage(
        val imageId: Int,
        val slotIndex: Int,
        val dataLen: Int,
        val crc32: String,
    )

    override val connectionState: StateFlow<ApConnectionState> = _connectionState.asStateFlow()
    override val incomingLines: SharedFlow<String> = _incoming.asSharedFlow()
    override val scannedDevices: StateFlow<List<BleDeviceInfo>> = _scanned.asStateFlow()

    override suspend fun startScan() {
        _connectionState.value = ApConnectionState.Scanning
        delay(200)
        _connectionState.value = ApConnectionState.Idle
    }

    override suspend fun stopScan() {
        if (_connectionState.value == ApConnectionState.Scanning) {
            _connectionState.value = ApConnectionState.Idle
        }
    }

    override suspend fun connect(address: String) {
        _connectionState.value = ApConnectionState.Connecting
        delay(120)
        _connectionState.value = ApConnectionState.Discovering
        delay(80)
        _connectionState.value = ApConnectionState.Subscribing
        delay(80)
        _connectionState.value = ApConnectionState.Ready
        emitEvent(JSONObject().put("event", "log").put("level", "I").put("msg", "Mock AP Ready ($address)"))
    }

    override suspend fun disconnect() {
        _connectionState.value = ApConnectionState.Disconnecting
        delay(50)
        _connectionState.value = ApConnectionState.Disconnected
        framer.reset()
    }

    override val attPayloadBytes: Int
        get() = 512

    override suspend fun sendLine(json: String) {
        writeMutex.withLock {
            val line = if (json.endsWith("\n")) json.trimEnd('\n') else json
            handleRequest(line)
        }
    }

    override suspend fun sendBytes(bytes: ByteArray, onProgress: ((Int) -> Unit)?) {
        writeMutex.withLock {
            when {
                badgeUploadImageId != null -> badgeUploadReceived += bytes.size
                uploadImageId != null -> uploadReceived += bytes.size
                else -> error("no upload session")
            }
            onProgress?.invoke(bytes.size)
        }
    }

    private suspend fun handleRequest(line: String) {
        val req = runCatching { JSONObject(line) }.getOrElse {
            replyError("", "invalid_argument", "bad json")
            return
        }
        val id = req.optString("id", "")
        val op = req.optString("op", "")
        val data = req.optJSONObject("data") ?: JSONObject()

        when (op) {
            "hello" -> replyOk(
                id,
                JSONObject()
                    .put("protocol", "esl-jsonl-v1")
                    .put("fw_ver", "0.1.0-mock")
                    .put("device_name", "ESP-ESL-AP-MOCK")
                    .put("max_chunk", 512)
                    .put("max_line", 1024)
                    .put("encrypted", false)
                    .put(
                        "capabilities",
                        JSONArray(
                            listOf(
                                "hello",
                                "get_ap_status",
                                "list_tags",
                                "scan_start",
                                "scan_stop",
                                "provision_tag",
                                "cancel_provision",
                                "unassociate",
                                "ping",
                                "led_control",
                                "display_image",
                                "refresh_display",
                                "upload_image_begin",
                                "upload_image_chunk",
                                "upload_image_end",
                                "transfer_image_to_tag",
                                "upload_raw",
                                "upload_badge_image_begin",
                                "upload_badge_image_chunk",
                                "upload_badge_image_end",
                                "transfer_badge_image_to_tag",
                                "list_badge_images",
                                "recovery-v1",
                                "list-tags-pagination",
                                "group-broadcast",
                            ),
                        ),
                    ),
            )
            "ping" -> replyOk(id, data)
            "esl" -> handleEsl(id, data)
            else -> replyError(id, "unsupported_cmd", "unknown op=$op")
        }
    }

    private suspend fun handleEsl(id: String, data: JSONObject) {
        when (data.optString("cmd")) {
            "get_ap_status" -> replyOk(
                id,
                JSONObject()
                    .put("started", true)
                    .put("scanning", false)
                    .put("pawr_active", true)
                    .put("tag_count", tags.size)
                    .put("selected_index", selectedIndex)
                    .put("phone_link", "connected")
                    .put("last_error", JSONObject.NULL)
                    .put("boot_id", "0123456789abcdef")
                    .put("recovery_phase", "ready")
                    .put("health", "degraded")
                    .put("orphan_bonds", JSONArray()),
            )
            "list_tags" -> {
                val offset = data.optInt("offset", 0).coerceAtLeast(0)
                val limit = if (data.has("limit")) data.optInt("limit", tags.size) else tags.size
                val end = (offset + limit).coerceAtMost(tags.size)
                val page = JSONArray()
                for (i in offset until end) {
                    page.put(tags[i])
                }
                replyOk(
                    id,
                    JSONObject()
                        .put("selected_index", selectedIndex)
                        .put("offset", offset)
                        .put("next_offset", end)
                        .put("total", tags.size)
                        .put("tags", page),
                )
            }
            "cancel_provision" -> {
                replyOk(id, JSONObject().put("status", "ok"))
            }
            "scan_start" -> {
                replyOk(id, JSONObject().put("status", "accepted"))
                scope.launch {
                    delay(300)
                    emitEvent(
                        JSONObject()
                            .put("event", "scan_result")
                            .put("ble_addr", "aa:bb:cc:dd:ee:99")
                            .put("addr_type", 0)
                            .put("rssi", -60)
                            .put("associated", false),
                    )
                }
            }
            "scan_stop" -> {
                replyOk(id, JSONObject().put("status", "accepted"))
            }
            "provision_tag" -> {
                val tagObj = data.optJSONObject("tag") ?: JSONObject()
                val bleAddr = tagObj.optString("ble_addr")
                if (bleAddr.isBlank()) {
                    replyError(id, "invalid_argument", "missing tag.ble_addr")
                    return
                }
                if (tags.any { it.optString("ble_addr").equals(bleAddr, ignoreCase = true) }) {
                    replyError(id, "invalid_argument", "tag already provisioned")
                    return
                }
                val eslId = if (data.has("esl_id")) data.optInt("esl_id") else tags.size
                val groupId = data.optInt("group_id", 0)
                if (tags.any { it.optInt("esl_id") == eslId && it.optInt("group_id") == groupId }) {
                    replyError(id, "invalid_argument", "esl_id already used")
                    return
                }
                replyOk(
                    id,
                    JSONObject()
                        .put("status", "accepted")
                        .put("esl_id", eslId)
                        .put("group_id", groupId),
                )
                scope.launch {
                    emitEvent(
                        JSONObject()
                            .put("event", "tag_state_changed")
                            .put("ble_addr", bleAddr)
                            .put("esl_id", eslId)
                            .put("group_id", groupId)
                            .put("state", "associating"),
                    )
                    delay(600)
                    tags.add(
                        JSONObject()
                            .put("ble_addr", bleAddr.lowercase())
                            .put("addr_type", tagObj.optInt("addr_type", 0))
                            .put("esl_id", eslId)
                            .put("group_id", groupId)
                            .put("esl_addr", eslId)
                            .put("state", "synchronized")
                            .put("current_image_index", 0)
                            .put("has_display", true)
                            .put("has_led", true)
                            .put("has_sensor", true)
                            .put("num_displays", 1)
                            .put("max_image_index", 3)
                            .put("display_width", 296)
                            .put("display_height", 128)
                            .put("display_type", 1)
                            .put("valid", true)
                            .put("product_state", "online")
                            .put("esl_runtime_state", "synchronized")
                            .put("associated", true)
                            .put("desired_state", "active")
                            .put("time_synced", true)
                            .put("recovery_generation", 1)
                            .put("last_seen_uptime_ms", 0)
                            .put("retry_count", 0)
                            .put("failure_reason", JSONObject.NULL)
                            .put("provision_phase", "active"),
                    )
                    emitEvent(
                        JSONObject()
                            .put("event", "tag_state_changed")
                            .put("ble_addr", bleAddr)
                            .put("esl_id", eslId)
                            .put("group_id", groupId)
                            .put("state", "synchronized"),
                    )
                }
            }
            "unassociate" -> {
                val tag = data.optJSONObject("tag") ?: JSONObject()
                val eslId = tag.optInt("esl_id", -1)
                val groupId = tag.optInt("group_id", 0)
                if (eslId == 255) {
                    replyError(id, "invalid_argument", "unassociate does not support group broadcast")
                    return
                }
                if (eslId < 0) {
                    replyError(id, "invalid_argument", "missing tag.esl_id")
                    return
                }
                val idx = tags.indexOfFirst {
                    it.optInt("esl_id") == eslId && it.optInt("group_id") == groupId
                }
                if (idx < 0) {
                    replyError(id, "tag_not_found", "tag not in inventory")
                    return
                }
                replyOk(id, JSONObject().put("status", "accepted"))
                scope.launch {
                    delay(500)
                    tags.removeAt(idx)
                    emitEvent(
                        JSONObject()
                            .put("event", "tag_state_changed")
                            .put("tag", tag)
                            .put("state", "unassociated"),
                    )
                    emitEvent(
                        JSONObject()
                            .put("event", "command_result")
                            .put("cmd", "unassociate")
                            .put("tag", tag)
                            .put("status", "ok"),
                    )
                }
            }
            "ping", "led_control", "display_image", "refresh_display" -> {
                val tag = data.optJSONObject("tag") ?: JSONObject()
                val eslId = tag.optInt("esl_id", -1)
                val groupId = tag.optInt("group_id", 0)
                val cmd = data.optString("cmd")
                val isDisplay = cmd == "display_image" || cmd == "refresh_display"
                if (eslId == 254) {
                    if (!isDisplay) {
                        replyError(id, "invalid_argument", "AP local id only supports display/refresh")
                        return
                    }
                    val displayed = applyApDisplay(cmd, data.optInt("image_index", 0))
                    if (!displayed) {
                        replyError(id, "image_invalid", "AP slot empty")
                        return
                    }
                    replyOk(id, JSONObject().put("status", "accepted"))
                    scope.launch {
                        emitEvent(commandResult(cmd, tag, apDisplayed = true, elapsedMs = 0))
                    }
                    return
                }
                val broadcast = eslId == 255
                if (broadcast) {
                    val anyOnline = tags.any {
                        it.optInt("group_id") == groupId &&
                            it.optString("product_state", "online") == "online"
                    }
                    if (!isDisplay && !anyOnline) {
                        replyError(id, "not_ready", "no online tag in group")
                        return
                    }
                } else {
                    val inv = tags.find {
                        it.optInt("esl_id") == eslId && it.optInt("group_id") == groupId
                    }
                    if (inv == null) {
                        replyError(id, "tag_not_found", "tag not in inventory")
                        return
                    }
                    if (inv.optString("product_state", "online") != "online") {
                        replyError(id, "not_ready", "tag product_state is not online")
                        return
                    }
                }
                val apDisplayed = if (broadcast && isDisplay) {
                    applyApDisplay(cmd, data.optInt("image_index", 0))
                } else {
                    false
                }
                replyOk(id, JSONObject().put("status", "accepted"))
                // Broadcast: fire-and-forget (immediate result). Unicast: short delay like Tag RTT.
                scope.launch {
                    if (!broadcast) {
                        delay(400)
                    }
                    emitEvent(
                        commandResult(
                            cmd,
                            tag,
                            apDisplayed = if (isDisplay) apDisplayed else null,
                            elapsedMs = if (broadcast) 0 else 400,
                        ),
                    )
                }
            }
            "upload_image_begin" -> {
                val imageId = data.optInt("image_id", 0)
                val version = data.optInt("version", 1)
                if (imageId !in 1..Int.MAX_VALUE || version !in 1..Int.MAX_VALUE) {
                    replyError(id, "image_invalid", "image_id/version out of int32 range")
                    return
                }
                uploadImageId = imageId
                uploadVersion = version
                uploadExpected = data.optInt("data_len")
                uploadChecksum = data.optString("checksum")
                uploadReceived = 0
                replyOk(id, JSONObject().put("status", "accepted"))
            }
            "upload_image_chunk" -> {
                val b64 = data.optString("data")
                val decoded = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                uploadReceived += decoded.size
                replyOk(id, JSONObject().put("status", "accepted").put("offset", data.optInt("offset")))
            }
            "upload_image_end" -> {
                val imageId = uploadImageId
                if (imageId == null) {
                    replyError(id, "not_ready", "no upload session")
                    return
                }
                val ok = uploadReceived == uploadExpected
                if (!ok) {
                    replyError(id, "image_invalid", "length mismatch $uploadReceived/$uploadExpected")
                    return
                }
                // Match AP: authoritative slot comes from upload_image_end.image_index (default 0).
                val imageIndex = data.optInt("image_index", 0).coerceIn(0, 3)
                runtimeImages[imageId] = RuntimeImage(
                    imageId = imageId,
                    imageIndex = imageIndex,
                    version = uploadVersion,
                    dataLen = uploadReceived,
                )
                // Keep at most 4 slots like AP; if a 5th unique id appears, drop oldest.
                while (runtimeImages.size > 4) {
                    val oldest = runtimeImages.keys.first()
                    runtimeImages.remove(oldest)
                }
                replyOk(id, JSONObject().put("status", "stored").put("image_id", imageId))
                emitEvent(
                    JSONObject()
                        .put("event", "image_uploaded")
                        .put("image_id", imageId)
                        .put("version", uploadVersion)
                        .put("data_len", uploadReceived)
                        .put("image_index", imageIndex),
                )
            }
            "transfer_image_to_tag" -> {
                val tag = data.optJSONObject("tag") ?: JSONObject()
                if (tag.optInt("esl_id", -1) == 255) {
                    replyError(id, "invalid_argument", "transfer_image_to_tag does not support group broadcast")
                    return
                }
                val imageId = data.optInt("image_id", 0)
                val stored = runtimeImages[imageId]
                if (stored == null) {
                    // Do not fake success with a built-in image (AP path fails/timeouts).
                    replyError(id, "image_invalid", "image not found")
                    return
                }
                replyOk(id, JSONObject().put("status", "accepted"))
                scope.launch {
                    delay(200)
                    emitEvent(
                        JSONObject()
                            .put("event", "image_transfer_progress")
                            .put("esl_id", tag.optInt("esl_id"))
                            .put("offset", stored.dataLen / 2)
                            .put("total", stored.dataLen),
                    )
                    delay(300)
                    emitEvent(
                        JSONObject()
                            .put("event", "command_result")
                            .put("cmd", "transfer_image_to_tag")
                            .put("tag", tag)
                            .put("status", "ok")
                            .put("elapsed_ms", 500)
                            .put("image_index", stored.imageIndex),
                    )
                }
            }
            "upload_badge_image_begin" -> {
                val imageId = data.optInt("image_id", 0)
                val dataLen = data.optInt("data_len", 0)
                val dest = data.optString("dest")
                if (
                    imageId !in 1..Int.MAX_VALUE ||
                    dataLen != 480 * 480 * 2 ||
                    data.optInt("width") != 480 ||
                    data.optInt("height") != 480
                ) {
                    replyError(id, "image_invalid", "badge image must be 480x480 RGB565")
                    return
                }
                if (dest != "ap" && dest != "tag") {
                    replyError(id, "invalid_argument", "dest must be ap or tag")
                    return
                }
                badgeUploadImageId = imageId
                badgeUploadSlot = data.optInt("slot_index", 0).coerceIn(0, 3)
                badgeUploadDest = dest
                badgeUploadExpected = dataLen
                badgeUploadReceived = 0
                badgeUploadCrc32 = data.optString("crc32")
                replyOk(id, JSONObject().put("status", "accepted"))
            }
            "upload_badge_image_chunk" -> {
                val imageId = badgeUploadImageId
                if (imageId == null || data.optInt("image_id") != imageId) {
                    replyError(id, "not_ready", "no matching badge upload session")
                    return
                }
                val decoded = android.util.Base64.decode(
                    data.optString("data_b64"),
                    android.util.Base64.DEFAULT,
                )
                badgeUploadReceived += decoded.size
                replyOk(id, JSONObject().put("status", "accepted").put("offset", data.optInt("offset")))
            }
            "upload_badge_image_end" -> {
                val imageId = badgeUploadImageId
                if (imageId == null || data.optInt("image_id") != imageId) {
                    replyError(id, "not_ready", "no matching badge upload session")
                    return
                }
                if (badgeUploadReceived != badgeUploadExpected) {
                    replyError(
                        id,
                        "image_invalid",
                        "length mismatch $badgeUploadReceived/$badgeUploadExpected",
                    )
                    return
                }
                val stored = BadgeImage(
                    imageId = imageId,
                    slotIndex = badgeUploadSlot,
                    dataLen = badgeUploadReceived,
                    crc32 = badgeUploadCrc32,
                )
                if (badgeUploadDest == "ap") {
                    badgeImages.entries.removeAll { it.value.slotIndex == stored.slotIndex }
                    badgeImages[imageId] = stored
                } else {
                    badgeForward[imageId] = stored
                }
                val dest = badgeUploadDest
                badgeUploadImageId = null
                replyOk(
                    id,
                    JSONObject()
                        .put("status", "stored")
                        .put("image_id", imageId)
                        .put("dest", dest),
                )
                emitEvent(
                    JSONObject()
                        .put("event", "image_uploaded")
                        .put("kind", "badge")
                        .put("image_id", imageId)
                        .put("dest", dest)
                        .put("slot_index", badgeUploadSlot)
                        .put("data_len", badgeUploadReceived),
                )
            }
            "list_badge_images" -> {
                val images = JSONArray()
                badgeImages.values.forEach { image ->
                    images.put(
                        JSONObject()
                            .put("image_id", image.imageId)
                            .put("slot_index", image.slotIndex)
                            .put("data_len", image.dataLen)
                            .put("crc32", image.crc32),
                    )
                }
                replyOk(id, JSONObject().put("images", images))
            }
            "transfer_badge_image_to_tag" -> {
                val tag = data.optJSONObject("tag") ?: JSONObject()
                val eslId = tag.optInt("esl_id", -1)
                val local = data.optBoolean("local", false) || eslId == 254
                val imageId = data.optInt("image_id", 0)
                val stored = if (local) badgeImages[imageId] else badgeForward[imageId]
                if (stored == null) {
                    replyError(
                        id,
                        "image_invalid",
                        if (local) "AP badge image not found" else "tag forward image not found",
                    )
                    return
                }
                val target = tags.find {
                    it.optInt("esl_id") == eslId &&
                        it.optInt("group_id") == tag.optInt("group_id", 0)
                }
                if (!local && (target == null || target.optInt("display_width") != 480 ||
                        target.optInt("display_height") != 480 || target.optInt("display_type") != 11)
                ) {
                    replyError(id, "invalid_argument", "target is not a 480x480 Full RGB tag")
                    return
                }
                replyOk(id, JSONObject().put("status", "accepted"))
                scope.launch {
                    emitEvent(
                        JSONObject()
                            .put("event", "badge_transfer_progress")
                            .put("esl_id", eslId)
                            .put("offset", stored.dataLen / 2)
                            .put("total", stored.dataLen),
                    )
                    delay(300)
                    if (!local) {
                        badgeForward.remove(imageId)
                    } else {
                        apCurrentSlot = stored.slotIndex
                    }
                    emitEvent(
                        JSONObject()
                            .put("event", "command_result")
                            .put("cmd", "transfer_badge_image_to_tag")
                            .put("tag", tag)
                            .put("status", "ok")
                            .put("slot_index", stored.slotIndex),
                    )
                }
            }
            else -> replyError(id, "unsupported_cmd", data.optString("cmd"))
        }
    }

    private fun badgeInSlot(slot: Int): BadgeImage? =
        badgeImages.values.firstOrNull { it.slotIndex == slot }

    private fun applyApDisplay(cmd: String, imageIndex: Int): Boolean {
        return if (cmd == "refresh_display") {
            val current = apCurrentSlot
            current != null && badgeInSlot(current) != null
        } else {
            val slot = imageIndex.coerceIn(0, 3)
            if (badgeInSlot(slot) == null) {
                false
            } else {
                apCurrentSlot = slot
                true
            }
        }
    }

    private fun commandResult(
        cmd: String,
        tag: JSONObject,
        apDisplayed: Boolean?,
        elapsedMs: Int,
    ): JSONObject {
        val obj = JSONObject()
            .put("event", "command_result")
            .put("cmd", cmd)
            .put("tag", tag)
            .put("status", "ok")
            .put("elapsed_ms", elapsedMs)
            .put(
                "basic_state",
                JSONObject()
                    .put("service_needed", false)
                    .put("synchronized", true)
                    .put("active_led", cmd == "led_control"),
            )
        if (apDisplayed != null) {
            obj.put("ap_displayed", apDisplayed)
        }
        return obj
    }

    private suspend fun replyOk(id: String, data: JSONObject) {
        emitLine(
            JSONObject()
                .put("v", 1)
                .put("id", id)
                .put("ok", true)
                .put("data", data)
                .toString(),
        )
    }

    private suspend fun replyError(id: String, code: String, message: String) {
        emitLine(
            JSONObject()
                .put("v", 1)
                .put("id", id)
                .put("ok", false)
                .put("error", code)
                .put("data", JSONObject().put("message", message))
                .toString(),
        )
    }

    private suspend fun emitEvent(obj: JSONObject) {
        emitLine(obj.toString())
    }

    private suspend fun emitLine(line: String) {
        _incoming.emit(line)
    }
}
