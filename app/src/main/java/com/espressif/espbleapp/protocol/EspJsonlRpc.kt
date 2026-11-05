package com.espressif.espbleapp.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

val EspJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

data class RpcRequest(
    val id: String,
    val op: String,
    val data: JsonObject = buildJsonObject { },
    val v: Int = 1,
) {
    fun toJsonLine(): String =
        buildJsonObject {
            put("v", v)
            put("id", id)
            put("op", op)
            put("data", data)
        }.toString()
}

sealed class IncomingMessage {
    data class RpcResponse(
        val id: String,
        val ok: Boolean,
        val data: JsonObject,
        val error: String?,
    ) : IncomingMessage()

    data class Event(
        val name: String,
        val raw: JsonObject,
    ) : IncomingMessage()

    data class Unsolicited(
        val raw: JsonObject,
    ) : IncomingMessage()
}

object EspJsonlRpc {
    fun parseIncoming(line: String): IncomingMessage {
        val obj = EspJson.parseToJsonElement(line).jsonObject
        val id = obj["id"]?.jsonPrimitive?.contentOrNull
        val okEl = obj["ok"]
        if (!id.isNullOrEmpty() && okEl is JsonPrimitive) {
            return IncomingMessage.RpcResponse(
                id = id,
                ok = okEl.content == "true" || okEl.booleanOrNull == true,
                data = obj["data"]?.jsonObject ?: buildJsonObject { },
                error = obj["error"]?.jsonPrimitive?.contentOrNull,
            )
        }
        val event = obj["event"]?.jsonPrimitive?.contentOrNull
        if (!event.isNullOrEmpty()) {
            return IncomingMessage.Event(name = event, raw = obj)
        }
        return IncomingMessage.Unsolicited(obj)
    }

    fun eslData(cmd: String, builder: (MutableMap<String, JsonElement>) -> Unit = {}): JsonObject {
        val map = mutableMapOf<String, JsonElement>("cmd" to JsonPrimitive(cmd))
        builder(map)
        return JsonObject(map)
    }

    fun tagSelector(eslId: Int, groupId: Int, bleAddr: String? = null): JsonObject =
        buildJsonObject {
            put("esl_id", eslId)
            put("group_id", groupId)
            if (!bleAddr.isNullOrBlank()) put("ble_addr", bleAddr)
        }
}

private val JsonPrimitive.booleanOrNull: Boolean?
    get() = when (content) {
        "true" -> true
        "false" -> false
        else -> null
    }

fun JsonObject.string(key: String, default: String = ""): String =
    this[key]?.jsonPrimitive?.contentOrNull ?: default

fun JsonObject.int(key: String, default: Int = 0): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: default

fun JsonObject.long(key: String, default: Long = 0L): Long =
    this[key]?.jsonPrimitive?.longOrNull ?: default

fun JsonObject.bool(key: String, default: Boolean = false): Boolean {
    val p = this[key]?.jsonPrimitive ?: return default
    return p.booleanOrNull ?: default
}

fun JsonObject.boolOrNull(key: String): Boolean? {
    val p = this[key]?.jsonPrimitive ?: return null
    return p.booleanOrNull
}

fun JsonObject.obj(key: String): JsonObject? = this[key]?.jsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
