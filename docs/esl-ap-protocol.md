# ESL AP Controller Protocol v0.1

> 中文版：[esl-ap-protocol-zh-CN.md](esl-ap-protocol-zh-CN.md)

JSONL over BLE UART (NUS). Envelope: `esp-jsonl-rpc-lite-v1`.

Wire: one UTF-8 JSON object + `\n` per message.

## Envelope

### Request (phone → AP)

```json
{"v":1,"id":"<string>","op":"<hello|esl|ping>","data":{}}
```

- `id`: non-empty for RPC; empty string = fire-and-forget (no response expected).
- `op`:
  - `hello` — capability handshake
  - `ping` — transport echo (`data` echoed back)
  - `esl` — ESL business; payload in `data` with `cmd`

### Sync response (AP → phone)

```json
{"v":1,"id":"<same>","ok":true,"data":{}}
{"v":1,"id":"<same>","ok":false,"error":"<code>","data":{"message":"optional"}}
```

### Async event (AP → phone, no `id` / not matched to pending)

```json
{"event":"<name>", ...}
```

Phone rules:

1. Message has `id` and boolean `ok` → complete pending request.
2. Message has `event` → UI / task queue event stream.
3. Otherwise → log as unsolicited.

## Global conventions

| Item | Value |
|------|--------|
| Protocol name | `esl-jsonl-v1` |
| Envelope version `v` | `1` |
| Single-flight | one pending non-empty `id` at a time; else `busy` |
| Sync ACK timeout (phone) | 5 s |
| Async command_result timeout | 30 s after `accepted` |
| `id` format | UUID string or `req-<monotonic>` |
| Tag address in UI | `ble_addr` as lowercase `aa:bb:cc:dd:ee:ff` |
| Default encryption | plaintext NUS for DevKit; SC later |

### Error codes (`error` string)

`invalid_argument` | `unsupported_cmd` | `busy` | `tag_not_found` | `not_connected` | `timeout` | `image_invalid` | `not_ready` | `internal` | `upgrade_required`

### Tag selector (`data.tag`)

Used by most ESL cmds:

```json
{
  "esl_id": 0,
  "group_id": 0,
  "ble_addr": "aa:bb:cc:dd:ee:ff"
}
```

- Synchronized / associated ops: prefer `esl_id` + `group_id`.
- Unassociated provision: require `ble_addr` (+ optional `addr_type`: 0 public / 1 random).
- **Group broadcast**: `esl_id == 255` (`0xFF`) addresses all ESLs in `group_id` over PAwR. Not a whole-network broadcast. Requires `hello.capabilities` token `group-broadcast`.

### Tag state strings (wire)

Map from `ble_esl_ap_esl_state_t`:

`unassociated` | `configuring` | `configured` | `synchronized` | `updating` | `unsynchronized` | `unknown`

---

## Commands (v0.1 freeze for milestone A/B)

### `op=hello`

Request:

```json
{"v":1,"id":"hello-1","op":"hello","data":{"client":"esl_ap_controller","client_ver":"0.1.0"}}
```

Response `data`:

```json
{
  "protocol": "esl-jsonl-v1",
  "fw_ver": "0.1.0",
  "device_name": "ESP-ESL-AP",
  "max_chunk": 512,
  "max_line": 1024,
  "encrypted": false,
  "capabilities": [
    "hello",
    "get_ap_status",
    "list_tags",
    "scan_start",
    "scan_stop",
    "ping",
    "led_control",
    "display_image",
    "refresh_display",
    "upload_raw",
    "upload_badge_image_begin",
    "upload_badge_image_chunk",
    "upload_badge_image_end",
    "transfer_badge_image_to_tag",
    "list_badge_images",
    "unassociate",
    "cancel_provision",
    "recovery-v1",
    "list-tags-pagination",
    "group-broadcast"
  ]
}
```

Capability tokens match `cmd` names App may call, plus feature tokens (`recovery-v1`, `list-tags-pagination`, `group-broadcast`, `upload_raw`). Missing token → App hides / disables UI.

`hello` does **not** start ESL/PAwR. Recovery starts independently on AP boot; hello only reports capabilities / refreshes observability.

### `op=ping` (transport)

Request: `{"v":1,"id":"p1","op":"ping","data":{"nonce":"abc"}}`  
Response: `{"v":1,"id":"p1","ok":true,"data":{"nonce":"abc"}}`

### `cmd=get_ap_status` (`op=esl`)

Request: `{"cmd":"get_ap_status"}`

Response `data` example:

```json
{
  "started": true,
  "scanning": true,
  "pawr_active": true,
  "tag_count": 2,
  "selected_index": 0,
  "phone_link": "connected",
  "last_error": null,
  "boot_id": "0123456789abcdef",
  "recovery_phase": "ready",
  "health": "healthy"
}
```

### `cmd=list_tags`

Request (legacy): `{"cmd":"list_tags"}`  
Request (paginated): `{"cmd":"list_tags","offset":0,"limit":2}`

Response `data`:

```json
{
  "selected_index": 0,
  "offset": 0,
  "next_offset": 1,
  "total": 2,
  "tags": [
    {
      "ble_addr": "aa:bb:cc:dd:ee:ff",
      "addr_type": 0,
      "esl_id": 0,
      "group_id": 0,
      "esl_addr": 0,
      "state": "synchronized",
      "current_image_index": 1,
      "has_display": true,
      "has_led": true,
      "has_sensor": false,
      "num_displays": 1,
      "max_image_index": 3,
      "display_width": 480,
      "display_height": 480,
      "display_type": 11,
      "valid": true,
      "product_state": "online",
      "esl_runtime_state": "synchronized",
      "associated": true,
      "desired_state": "active",
      "time_synced": true,
      "recovery_generation": 1,
      "last_seen_uptime_ms": 12000,
      "retry_count": 0,
      "failure_reason": null,
      "provision_phase": "active"
    }
  ]
}
```

Fields align with inventory wire+runtime (keys/secrets **not** exposed to phone).
Display fields are optional on older firmware. The APP enables the Mosaico badge path only for
`display_width=480`, `display_height=480`, and Full RGB `display_type=11` (`0x0B`).

Legacy `state`: `"synchronized"` only when `product_state=="online" && time_synced`; otherwise `"unsynchronized"`.

---

## recovery-v1 (observability)

Advertised via `hello.capabilities` tokens `recovery-v1` and `list-tags-pagination`.

### `get_ap_status` / recovery summary fields

| Field | Type | Notes |
|-------|------|-------|
| `boot_id` | string | 16-hex `uint64` |
| `recovery_phase` | string | `booting\|recovering\|ready\|failed` |
| `health` | string | `healthy\|degraded` |
| `pawr_active` | bool | mirrors `started` / PAwR running |
| `retryable` / `error_code` / `retry_count` / `next_retry_uptime_ms` / `recommended_action` | mixed | only when `recovery_phase=="failed"` |
| `orphan_bonds` | array | optional diagnostic; `{addr, addr_type, first_seen_uptime_ms}`; no remote delete |

### Tag fields (`list_tags` + `tag_recovery_changed`)

| Field | Type | Notes |
|-------|------|-------|
| `product_state` | string | `recovering\|online\|retry_wait\|offline\|quarantined` |
| `esl_runtime_state` | string | ESL runtime enum |
| `associated` | bool | |
| `desired_state` | string | `active\|disabled\|retired` |
| `time_synced` | bool | |
| `recovery_generation` | uint | |
| `last_seen_uptime_ms` | uint | current `boot_id` monotonic domain |
| `retry_count` | uint | |
| `failure_reason` | string\|null | |
| `provision_phase` | string | `prepared\|bonded\|configured\|active` |

### Pagination

- New APP detecting `list-tags-pagination` sends `offset`/`limit`.
- Response always includes `offset`, `next_offset`, `total` when recovery-v1 AP answers.
- Old APP without pagination: if full list cannot fit `GW_TX_LINE_MAX`, AP returns `error=upgrade_required` (never silently truncates incomplete JSON).

### Control gating

PAwR cmds (`ping`, `led_control`, `display_image`, `refresh_display`):

- **Unicast Tag** (`esl_id` 0–253): return `not_ready` when that tag’s `product_state != online`.
- **AP local** (`esl_id` 254): not an ESL Tag and not in `list_tags`. App picker shows a virtual AP row. Only `display_image` / `refresh_display` are valid; ping / LED / unassociate are rejected. Empty AP slot → error. Gateway executes locally (no PAwR).
- **Group broadcast** (`esl_id` 255): do not look up inventory by `esl_id=255`. For ping / LED, return `not_ready` when the inventory has **no** valid tag in that `group_id` with `product_state=online`. For `display_image` / `refresh_display`, App still sends 255 even with no online Tag so the gateway can switch AP. Sync `accepted`, then a single `command_result` with `tag.esl_id=255`. `status=ok` means the PAwR payload was queued when Tags exist (not per-tag ACK). `ap_displayed=true|false` reports whether the AP screen changed. An empty AP slot is skipped and does not fail the group command.
- `unassociate` / `transfer_image_to_tag` with `esl_id=255` return `invalid_argument` (ECP/ACL only; no broadcast).

### `cmd=cancel_provision`

Only cancels `PREPARED` records with no bond; releases the slot. Cannot delete ACTIVE/BONDED records. There is **no** `transfer_ownership` command.

### Events

```json
{"event":"recovery_summary_changed","boot_id":"...","recovery_phase":"ready","health":"degraded","pawr_active":true}
{"event":"tag_recovery_changed","boot_id":"...","slot":0,"tag":{...}}
```

APP should refresh `get_ap_status` / `list_tags` on these events.

### Compatibility

| Pairing | Behavior |
|---------|----------|
| New AP × old APP | Old APP ignores unknown fields; may hit `upgrade_required` on large `list_tags` without pagination |
| Old AP × new APP | New APP treats missing recovery fields as `recoveryPhase="unknown"`; controls use legacy `state==synchronized` |
| New AP × new APP | Full recovery-v1 + pagination |

---

## Commands (continued)

### `cmd=scan_start`

Request: `{"cmd":"scan_start"}`  
Sync: `{"status":"accepted"}`  
Events: `scan_result` (may be rate-limited by firmware).

Phone App must send this explicitly (Tags page **Scan** button). AP does **not** start GAP discovery on `hello` / ESL AP boot; only PAwR runs until `scan_start`.

```json
{"event":"scan_result","ble_addr":"aa:bb:cc:dd:ee:ff","addr_type":0,"rssi":-55,"associated":false}
```

### `cmd=scan_stop`

Request: `{"cmd":"scan_stop"}`  
Sync: `{"status":"accepted"}`  

Stops AP GAP discovery. PAwR broadcasting continues. Discovery is not auto-resumed after connections end until `scan_start` is sent again. Returns `busy` when provisioning, connecting, or unassociating.

### `cmd=provision_tag`

Request:

```json
{
  "cmd": "provision_tag",
  "tag": {"ble_addr": "aa:bb:cc:dd:ee:ff", "addr_type": 0},
  "esl_id": 1,
  "group_id": 0
}
```

- `tag.ble_addr` required (from `scan_result`).
- `esl_id` / `group_id` optional on the wire; AP auto-allocates next free `esl_id` when omitted.
- **APP UX (cluster LED demo):** tapping Provision on a scan result **must** pick a fixed Group from **0 / 1 / 2 / 3** (no free-form input). The chosen value is always sent as `group_id`.

Sync: `{"status":"accepted","esl_id":1,"group_id":0}`

Async events:

```json
{"event":"tag_state_changed","ble_addr":"aa:bb:cc:dd:ee:ff","esl_id":1,"group_id":0,"state":"synchronized"}
```

On failure:

```json
{"event":"provision_failed","ble_addr":"aa:bb:cc:dd:ee:ff","reason":"connect_timeout"}
```

### `cmd=ping` (ESL, inside `op=esl`)

Request: `{"cmd":"ping","tag":{"esl_id":0,"group_id":0}}`  
Group broadcast: `{"cmd":"ping","tag":{"esl_id":255,"group_id":0}}`  
Sync: `{"status":"accepted"}`  
Event:

```json
{"event":"command_result","cmd":"ping","tag":{"esl_id":0,"group_id":0},"status":"ok","basic_state":{"service_needed":false,"synchronized":true,"active_led":false}}
```

or `status":"timeout"`. Broadcast: one `command_result` with `esl_id=255` and `status=ok|error` (no timeout wait for Tag replies).

### `cmd=led_control`

Request (steady):

```json
{
  "cmd": "led_control",
  "tag": {"esl_id": 0, "group_id": 0},
  "led_index": 0,
  "color_red": 0,
  "color_green": 3,
  "color_blue": 0,
  "brightness": 3,
  "repeat_type": 1,
  "repeats_duration": 0
}
```

Request (flashing / Blink — one PAwR command; Tag steps bits locally):

```json
{
  "cmd": "led_control",
  "tag": {"esl_id": 255, "group_id": 1},
  "led_index": 0,
  "color_red": 3,
  "color_green": 0,
  "color_blue": 0,
  "brightness": 3,
  "repeat_type": 1,
  "repeats_duration": 10,
  "pattern": "AAAAAAAAAA",
  "bit_off_period": 125,
  "bit_on_period": 125
}
```

- Omit colors → AP may use firmware Kconfig defaults.
- `repeat_type=1` + `repeats_duration=0` → steady on (same as AP demo). Zero `flashing_pattern` remains the steady path.
- Flashing fields (optional together):
  - `pattern`: exactly **10 hex chars** = 40-bit pattern as **5 little-endian bytes**.
  - `bit_off_period` / `bit_on_period`: **1–255**, units of **2 ms** (125 → 250 ms).
  - `repeats_duration` must be **> 0** when `pattern` is present (`repeat_type=0` = count, `1` = seconds).
- Group broadcast: same params with `"esl_id": 255`. APP Group scope still requires selecting an **online** Tag so `group_id` is known.
- **Broadcast semantics:** `command_result` with `esl_id=255` means the command was **queued into PAwR**, not that every Tag received or finished the pattern. Use Tag logs / visual / external GPIO for delivery.

Sync: `accepted`. Event: `command_result` with `status` ok/timeout/error (broadcast: ok/error only, fire-and-forget).

### `cmd=display_image`

Request:

```json
{
  "cmd": "display_image",
  "tag": {"esl_id": 0, "group_id": 0},
  "display_index": 0,
  "image_index": 1
}
```

Group broadcast example: `"tag": {"esl_id": 255, "group_id": 0}` with the same `display_index` / `image_index`.

APP UX:

- Selected **AP** → send `esl_id=254`, `group_id=0`. Group scope is disabled.
- Selected **Tag** + Selected scope → unicast that Tag only. AP does not change.
- Entire group → send **only** `esl_id=255` for the selected Tag’s `group_id`. App does not send a second 254; the gateway switches AP to the same `image_index`. Empty AP slot is skipped (`ap_displayed=false`).

### `cmd=refresh_display`

Request:

```json
{
  "cmd": "refresh_display",
  "tag": {"esl_id": 0, "group_id": 0},
  "display_index": 0
}
```

Group broadcast: `"esl_id": 255` with the same `display_index`.

`refresh_display` has no `image_index`. AP refresh redraws the AP **current** image only; it does not change slot. Same target rules as `display_image`.

---

## Commands (v0.2 — after gateway image path)

### `upload_image_begin|chunk|end`

`begin`: `image_id`, `version`, `data_len`, `checksum` (hex CRC32), `format` (`raw`).

When hello includes `upload_raw`, this APP does **not** send `upload_image_chunk`. After `begin` is accepted, it writes exactly `data_len` raw bytes on NUS RX, then sends `end`. The AP treats those bytes as the image payload (not JSONL) until `data_len` is reached.

`chunk` remains available as a fallback: `image_id`, `version`, `offset`, `data` (base64). Current firmware still accepts it after the raw stream has finished.

`end`: verify checksum → store via `image_manager`. Optional but **required by this APP**: `image_index` (0–3). The AP runtime descriptor uses this value as the authoritative Tag OTS slot. Omitting it defaults to `0` on current firmware.

#### APP preprocessing (not part of the wire payload)

Custom gallery images (PNG/JPEG/WebP) are decoded, oriented, scaled (CenterCrop or FitCenter), thresholded or Floyd–Steinberg dithered, and packed to 1bpp **inside the Android APP**. The AP and Tag never decode photos; they only receive `format=raw` 4736-byte frames.

#### Raw frame convention (SSD1680)

- `data_len`: **4736** (`128 × 296 / 8`)
- Byte layout: linear 1bpp matching the Tag `epaper` framebuffer (16 bytes/row × 296 rows)
- ESL Display Information: width 296, height 128, type B/W
- AP gateway receive buffer and runtime slot max are both 4736
- Bit polarity: white = 1, black = 0 (same as the demo checkerboard)

#### `image_id` / `version` (int32) and AP 4-slot cache

AP gateway parses `image_id` and `version` with `atoi()` into 32-bit `int`. Clients **must** send values in `1..2147483647` (`Int.MAX_VALUE`). Do **not** send millisecond timestamps.

AP `esl_image_manager` keeps only **4 runtime slots**, preferring reuse by `image_id`. This APP therefore binds each display slot to a fixed positive ID:

| `image_index` | `image_id` |
|---------------|------------|
| 0 | 1001 |
| 1 | 1002 |
| 2 | 1003 |
| 3 | 1004 |

Re-uploading the same slot overwrites that ID in place. These four IDs are AP-global “latest slot templates”, not per-Tag caches: after OTS, content lives on the Tag slot, but a later transfer of the same slot uses whatever is currently stored on the AP for that ID.

`version` is the CRC32 of the raw payload, low 31 bits, with `0` mapped to `1` (stable for identical content, usually new when content changes, always positive int32).

#### Temporary transport and future migration

> Image data now uses **NUS raw bytes** after JSONL `begin` (ATT MTU 517, one JSONL ACK at `end`). Android writes those bytes with Write Command (no ATT response), each at most 512 bytes (`GATT_MAX_ATTR_LEN`). JSONL control lines still use Write Request. JSONL/Base64 chunks are the fallback when `upload_raw` is absent.
>
> Evolution: keep JSONL/NUS as the command/metadata/progress control plane; a later APP→AP data plane may move to an **Espressif general-purpose transfer component**. AP→Tag OTS/L2CAP CoC stays unchanged.

### `transfer_image_to_tag`

Needs synchronized→updating path (`connect_synced` + OTS). Event: `image_transfer_progress` / `command_result`.

Request fields: `tag`, `image_id`, and `image_index` (diagnostics / protocol compatibility). **Current firmware does not redirect the OTS target slot from this request field**; the slot comes from the runtime image descriptor written at `upload_image_end`. This APP sends the same `image_index` on both commands. Missing `image_id` fails (timeout / not-found); it does **not** silently transfer a built-in default image. `esl_id=255` → `invalid_argument` (no group broadcast).

### Mosaico badge image commands

- `upload_badge_image_begin`: `image_id`, `slot_index`, `dest` (`ap` or `tag`), `data_len=460800`, `crc32` (8-digit hex string), `width=480`, `height=480`. AP IDs use 2001–2004; Tag-forward IDs use 3001–3004. The two stores never share a frame. `begin` ACK may include `"encoding":"raw"`.
- With `upload_raw`: after `begin`, the phone writes exactly 460800 raw RGB565 bytes on NUS RX, then `upload_badge_image_end`. No per-chunk JSONL.
- `upload_badge_image_chunk`: fallback only; `image_id`, `offset`, `data_b64`.
- `upload_badge_image_end`: `image_id`. `dest=ap` stores the slot; the AP screen changes only on a later display command. `dest=tag` only fills the transient forward buffer.
- `transfer_badge_image_to_tag`: `tag` (`esl_id`, `group_id`), `image_id`, `slot_index`; optional `local=true` selects AP self-display (including `esl_id=254`) from the AP store. A Tag transfer reads the forward buffer and the AP drops that RAM copy when OTS finishes.
- `list_badge_images`: no additional request fields; response lists only persistent AP images.

Badge payloads are center-cropped by the APP to 480×480 and encoded as RGB565, little-endian
per 16-bit pixel (`low byte`, then the byte containing the red high bits). CRC32 is IEEE reflected
(the same value produced by Java `CRC32` and ESP `esp_crc32_le`). Upload/transfer may reuse
`image_uploaded`, `image_transfer_progress`, and `command_result`, or emit their mirrored badge events.

### `cmd=unassociate`

Request:

```json
{"cmd":"unassociate","tag":{"esl_id":1,"group_id":0}}
```

`esl_id=255` → `invalid_argument` (ECP-only; no group broadcast).

Sync: `{"status":"accepted"}`  
Async: `command_result` with `status=ok|error`. Success is confirmed by the ECP Basic State response, the target entering `unassociated`, or its complete/connectable ESL Service advertisement after disconnect.

AP connects to the Tag if needed (synchronized tags use PAwR connection procedure) and sends ESL Unassociate (opcode 0x01). A disconnect after ECP dispatch enters a short confirmation window instead of failing immediately. Confirmed success removes the AP bond and NVS inventory record; timeout/error retains both for recovery or retry.

### Later: `read_sensor`, `factory_reset`, `get_logs`

Schema TBD when firmware APIs exposed through gateway.

---

## Events summary

| event | when |
|-------|------|
| `scan_result` | discovery |
| `tag_state_changed` | lifecycle |
| `command_result` | PAwR cmd done / timeout |
| `image_transfer_progress` | OTS |
| `image_uploaded` | upload session complete |
| `log` | diagnostic line |
| `ap_status_changed` | optional push after start/stop |
| `recovery_summary_changed` | AP lifecycle / health changed |
| `tag_recovery_changed` | per-tag product_state / recovery fields changed |

```json
{"event":"tag_state_changed","tag":{"esl_id":0,"group_id":0,"ble_addr":"..."},"state":"synchronized"}
{"event":"log","level":"W","msg":"PAwR timeout"}
```
