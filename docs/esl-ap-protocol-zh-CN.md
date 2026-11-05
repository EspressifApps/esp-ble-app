# ESL AP Controller 协议 v0.1

> 英文原文：[esl-ap-protocol.md](esl-ap-protocol.md)

基于 BLE UART（NUS）的 JSONL 协议。信封：`esp-jsonl-rpc-lite-v1`。

线路格式：每条消息为一个 UTF-8 JSON 对象，并以 `\n` 结尾。

## 信封

### 请求（手机 → AP）

```json
{"v":1,"id":"<string>","op":"<hello|esl|ping>","data":{}}
```

- `id`：非空表示 RPC；空字符串表示 fire-and-forget（不期待响应）。
- `op`：
  - `hello` — 能力握手
  - `ping` — 传输层回显（原样返回 `data`）
  - `esl` — ESL 业务；载荷在 `data` 中，含 `cmd`

### 同步响应（AP → 手机）

```json
{"v":1,"id":"<same>","ok":true,"data":{}}
{"v":1,"id":"<same>","ok":false,"error":"<code>","data":{"message":"optional"}}
```

### 异步事件（AP → 手机，无 `id` / 不匹配挂起请求）

```json
{"event":"<name>", ...}
```

手机侧规则：

1. 消息含 `id` 且含布尔 `ok` → 完成挂起请求。
2. 消息含 `event` → 进入 UI / 任务队列事件流。
3. 其它 → 记为非预期报文并打日志。

## 全局约定

| 项 | 值 |
|------|--------|
| 协议名 | `esl-jsonl-v1` |
| 信封版本 `v` | `1` |
| 单飞（single-flight） | 同一时刻仅允许一个非空 `id` 挂起；否则返回 `busy` |
| 同步 ACK 超时（手机） | 5 s |
| 异步 `command_result` 超时 | `accepted` 后 30 s |
| `id` 格式 | UUID 字符串或 `req-<monotonic>` |
| UI 中 Tag 地址 | `ble_addr` 小写 `aa:bb:cc:dd:ee:ff` |
| 默认加密 | DevKit 明文 NUS；后续再上 SC |

### 错误码（`error` 字符串）

`invalid_argument` | `unsupported_cmd` | `busy` | `tag_not_found` | `not_connected` | `timeout` | `image_invalid` | `not_ready` | `internal` | `upgrade_required`

### Tag 选择器（`data.tag`）

多数 ESL 命令使用：

```json
{
  "esl_id": 0,
  "group_id": 0,
  "ble_addr": "aa:bb:cc:dd:ee:ff"
}
```

- 已同步 / 已关联操作：优先 `esl_id` + `group_id`。
- 未关联配网：必须提供 `ble_addr`（可选 `addr_type`：0 public / 1 random）。
- **组播**：`esl_id == 255`（`0xFF`）表示对该 `group_id` 内全部 ESL 的 PAwR 组播，不是全网广播。需要 `hello.capabilities` 含 `group-broadcast`。

### Tag 状态字符串（线路）

映射自 `ble_esl_ap_esl_state_t`：

`unassociated` | `configuring` | `configured` | `synchronized` | `updating` | `unsynchronized` | `unknown`

---

## 命令（v0.1，里程碑 A/B 冻结）

### `op=hello`

请求：

```json
{"v":1,"id":"hello-1","op":"hello","data":{"client":"esl_ap_controller","client_ver":"0.1.0"}}
```

响应 `data`：

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

能力 token 与 App 可调用的 `cmd` 名一致，另有特性 token（`recovery-v1`、`list-tags-pagination`、`group-broadcast`、`upload_raw`）。缺少 token → App 隐藏 / 禁用对应 UI。

`hello` **不会**启动 ESL/PAwR。恢复逻辑在 AP 上电后独立启动；hello 仅上报能力 / 刷新可观测性。

### `op=ping`（传输层）

请求：`{"v":1,"id":"p1","op":"ping","data":{"nonce":"abc"}}`  
响应：`{"v":1,"id":"p1","ok":true,"data":{"nonce":"abc"}}`

### `cmd=get_ap_status`（`op=esl`）

请求：`{"cmd":"get_ap_status"}`

响应 `data` 示例：

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

请求（旧式）：`{"cmd":"list_tags"}`  
请求（分页）：`{"cmd":"list_tags","offset":0,"limit":2}`

响应 `data`：

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

字段与库存 wire+runtime 对齐（密钥/密文 **不会** 暴露给手机）。

旧式 `state`：仅当 `product_state=="online" && time_synced` 时为 `"synchronized"`；否则为 `"unsynchronized"`。

---

## recovery-v1（可观测性）

通过 `hello.capabilities` 中的 `recovery-v1` 与 `list-tags-pagination` 宣告。

### `get_ap_status` / 恢复摘要字段

| 字段 | 类型 | 说明 |
|-------|------|-------|
| `boot_id` | string | 16 位十六进制 `uint64` |
| `recovery_phase` | string | `booting\|recovering\|ready\|failed` |
| `health` | string | `healthy\|degraded` |
| `pawr_active` | bool | 与 `started` / PAwR 运行状态一致 |
| `retryable` / `error_code` / `retry_count` / `next_retry_uptime_ms` / `recommended_action` | 混合 | 仅当 `recovery_phase=="failed"` |
| `orphan_bonds` | array | 可选诊断；`{addr, addr_type, first_seen_uptime_ms}`；不可远程删除 |

### Tag 字段（`list_tags` + `tag_recovery_changed`）

| 字段 | 类型 | 说明 |
|-------|------|-------|
| `product_state` | string | `recovering\|online\|retry_wait\|offline\|quarantined` |
| `esl_runtime_state` | string | ESL runtime 枚举 |
| `associated` | bool | |
| `desired_state` | string | `active\|disabled\|retired` |
| `time_synced` | bool | |
| `recovery_generation` | uint | |
| `last_seen_uptime_ms` | uint | 当前 `boot_id` 单调时间域 |
| `retry_count` | uint | |
| `failure_reason` | string\|null | |
| `provision_phase` | string | `prepared\|bonded\|configured\|active` |

### 分页

- 新 APP 检测到 `list-tags-pagination` 后发送 `offset`/`limit`。
- recovery-v1 AP 响应始终包含 `offset`、`next_offset`、`total`。
- 无分页的旧 APP：若完整列表无法装入 `GW_TX_LINE_MAX`，AP 返回 `error=upgrade_required`（绝不静默截断不完整 JSON）。

### 控制门控

PAwR 命令（`ping`、`led_control`、`display_image`、`refresh_display`）：

- **单播 Tag**（`esl_id` 0–253）：该 Tag `product_state != online` 时返回 `not_ready`。
- **AP 本机**（`esl_id` 254）：不是 ESL Tag，不进 `list_tags`。App 选定列表有虚拟 AP 项。仅 `display_image` / `refresh_display` 有效；ping / LED / 解关联拒绝。AP 空槽 → error。网关本地执行，不走 PAwR。
- **组播**（`esl_id` 255）：不按 `esl_id=255` 查库存。ping / LED 在该 `group_id` 下没有 `product_state=online` 的有效 Tag 时返回 `not_ready`。`display_image` / `refresh_display` 即使组内没有 online Tag，App 仍发 255，以便网关顺带切 AP。同步 `accepted`，随后一条 `command_result`（`tag.esl_id=255`）。`status=ok` 表示有 Tag 时 PAwR 载荷已入队，**不是**逐 Tag 确认。`ap_displayed=true|false` 表示 AP 是否上屏。AP 空槽跳过，整单不失败。
- `unassociate` / `transfer_image_to_tag` 使用 `esl_id=255` 时返回 `invalid_argument`（仅 ECP/ACL，不支持组播）。

### `cmd=cancel_provision`

仅取消无 bond 的 `PREPARED` 记录并释放槽位。不能删除 ACTIVE/BONDED 记录。**没有** `transfer_ownership` 命令。

### 事件

```json
{"event":"recovery_summary_changed","boot_id":"...","recovery_phase":"ready","health":"degraded","pawr_active":true}
{"event":"tag_recovery_changed","boot_id":"...","slot":0,"tag":{...}}
```

APP 应在这些事件上刷新 `get_ap_status` / `list_tags`。

### 兼容性

| 组合 | 行为 |
|---------|----------|
| 新 AP × 旧 APP | 旧 APP 忽略未知字段；大列表无分页时可能遇到 `upgrade_required` |
| 旧 AP × 新 APP | 新 APP 将缺失恢复字段视为 `recoveryPhase="unknown"`；控制仍用旧式 `state==synchronized` |
| 新 AP × 新 APP | 完整 recovery-v1 + 分页 |

---

## 命令（续）

### `cmd=scan_start`

请求：`{"cmd":"scan_start"}`  
同步：`{"status":"accepted"}`  
事件：`scan_result`（固件可能限流）。

手机 App 必须显式发送（Tags 页 **Scan** 按钮）。AP **不会**在 `hello` / ESL AP 启动时开始 GAP discovery；在 `scan_start` 之前仅运行 PAwR。

```json
{"event":"scan_result","ble_addr":"aa:bb:cc:dd:ee:ff","addr_type":0,"rssi":-55,"associated":false}
```

### `cmd=scan_stop`

请求：`{"cmd":"scan_stop"}`  
同步：`{"status":"accepted"}`  

停止 AP GAP discovery。PAwR 广播继续。连接结束后不会自动恢复 discovery，需再次 `scan_start`。配网、连接或解关联进行中返回 `busy`。

### `cmd=provision_tag`

请求：

```json
{
  "cmd": "provision_tag",
  "tag": {"ble_addr": "aa:bb:cc:dd:ee:ff", "addr_type": 0},
  "esl_id": 1,
  "group_id": 0
}
```

- `tag.ble_addr` 必填（来自 `scan_result`）。
- 线缆上 `esl_id` / `group_id` 可选；省略时 AP 自动分配下一个空闲 `esl_id`。
- **APP UX（集群灯控 demo）：** 扫描结果点「配网」时必须从固定 **Group 0 / 1 / 2 / 3** 中选择（不可自由输入），并始终把所选值作为 `group_id` 下发。

同步：`{"status":"accepted","esl_id":1,"group_id":0}`

异步事件：

```json
{"event":"tag_state_changed","ble_addr":"aa:bb:cc:dd:ee:ff","esl_id":1,"group_id":0,"state":"synchronized"}
```

失败时：

```json
{"event":"provision_failed","ble_addr":"aa:bb:cc:dd:ee:ff","reason":"connect_timeout"}
```

### `cmd=ping`（ESL，位于 `op=esl` 内）

请求：`{"cmd":"ping","tag":{"esl_id":0,"group_id":0}}`  
组播：`{"cmd":"ping","tag":{"esl_id":255,"group_id":0}}`  
同步：`{"status":"accepted"}`  
事件：

```json
{"event":"command_result","cmd":"ping","tag":{"esl_id":0,"group_id":0},"status":"ok","basic_state":{"service_needed":false,"synchronized":true,"active_led":false}}
```

或 `status":"timeout"`。组播：一条 `command_result`，`esl_id=255`，`status=ok|error`（不等待 Tag 应答超时）。

### `cmd=led_control`

请求（常亮）：

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

请求（闪烁 / Blink — 单条 PAwR；Tag 本地按 bit 执行）：

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

- 省略颜色 → AP 可能使用固件 Kconfig 默认值。
- `repeat_type=1` + `repeats_duration=0` → 常亮（与 AP demo 一致）。全零 `flashing_pattern` 仍走常亮路径。
- 闪烁可选字段（需一起提供）：
  - `pattern`：恰好 **10 个十六进制字符** = 40-bit pattern（**5 字节小端**）。
  - `bit_off_period` / `bit_on_period`：**1–255**，单位 **2 ms**（125 → 250 ms）。
  - 有 `pattern` 时 `repeats_duration` 必须 **> 0**（`repeat_type=0` 按次数，`1` 按秒）。
- 组播：相同参数，`"esl_id": 255`。APP Group 模式仍需先选一个**在线** Tag 以确定 `group_id`。
- **组播语义：** `command_result`（`esl_id=255`）只表示命令已**排入 PAwR**，不代表所有 Tag 已收到或执行完毕。送达/执行请看 Tag 日志、目视或外接 GPIO。

同步：`accepted`。事件：`command_result`，`status` 为 ok/timeout/error（组播仅为 ok/error，fire-and-forget）。

### `cmd=display_image`

请求：

```json
{
  "cmd": "display_image",
  "tag": {"esl_id": 0, "group_id": 0},
  "display_index": 0,
  "image_index": 1
}
```

组播示例：`"tag": {"esl_id": 255, "group_id": 0}`，`display_index` / `image_index` 相同。

APP UX：

- 选中 **AP** → 发 `esl_id=254`、`group_id=0`。整组禁用。
- 选中 **某个 Tag** + 单个 → 只切该 Tag，AP 不动。
- 整组 → **只发一条** `esl_id=255`（用当前选中 Tag 的 `group_id`）。App 不再另发 254；网关顺带把 AP 切到同一 `image_index`。AP 空槽跳过（`ap_displayed=false`）。

### `cmd=refresh_display`

请求：

```json
{
  "cmd": "refresh_display",
  "tag": {"esl_id": 0, "group_id": 0},
  "display_index": 0
}
```

组播：`"esl_id": 255`，相同 `display_index`。

`refresh_display` 没有 `image_index`。整组 refresh 只重画 AP **当前**画面，不改槽。目标规则与 `display_image` 相同。

---

## 命令（v0.2 — gateway 图像路径之后）

### `upload_image_begin|chunk|end`

`begin`：`image_id`、`version`、`data_len`、`checksum`（十六进制 CRC32）、`format`（`raw`）。

hello 含 `upload_raw` 时，本 APP **不再**发送 `upload_image_chunk`。`begin` 被接受后，在 NUS RX 上写入恰好 `data_len` 字节原始数据，再发 `end`。AP 在收满 `data_len` 之前把这些字节当图像载荷，不当 JSONL。

`chunk` 仍可作为回退：`image_id`、`version`、`offset`、`data`（base64）。当前固件在原始流结束后仍接受该命令。

`end`：校验 checksum → 经 `image_manager` 落盘。可选但**本 APP 必填**：`image_index`（0–3）。AP runtime descriptor 以此作为权威的 Tag OTS 目标槽位；当前固件缺省为 `0`。

#### APP 预处理（不属于线缆载荷）

相册图片（PNG/JPEG/WebP）在 **Android APP 内**完成解码、方向校正、缩放（CenterCrop / FitCenter）、阈值或 Floyd–Steinberg 抖动，并打包为 1bpp。AP 与 Tag **不**解码照片，只接收 `format=raw` 的 4736 字节帧。

#### raw 帧约定（SSD1680）

- `data_len`：**4736**（`128 × 296 / 8`）
- 字节序：与 Tag `epaper` 驱动 framebuffer 一致的线性 1bpp（16 字节/行 × 296 行）
- ESL Display Information：宽 296、高 128、类型 B/W
- AP 网关接收缓冲与 runtime 槽上限均为 4736
- 位极性：白 = 1、黑 = 0（与演示棋盘格一致）

#### `image_id` / `version`（int32）与 AP 四槽缓存

AP gateway 用 `atoi()` 将 `image_id`、`version` 解析为 32 位 `int`。客户端**必须**发送 `1..2147483647`（`Int.MAX_VALUE`），**禁止**发送毫秒时间戳。

AP `esl_image_manager` 仅保留 **4 个 runtime 槽**，并优先按 `image_id` 复用。本 APP 因此将显示槽位与固定正数 ID 一一绑定：

| `image_index` | `image_id` |
|---------------|------------|
| 0 | 1001 |
| 1 | 1002 |
| 2 | 1003 |
| 3 | 1004 |

重复上传同一槽会原位覆盖该 ID。这 4 个 ID 是 AP **全局**“最新槽位模板”，不是每个 Tag 独立缓存：经 OTS 传到 Tag 后内容留在该 Tag 槽位，但之后再传同一槽会使用 AP 上该 ID 的最新内容。

`version` 取 raw 载荷 CRC32 的低 31 位，`0` 映射为 `1`（相同内容版本稳定，内容变化通常产生新版本，且始终为正 int32）。

#### 临时传输方案与后续迁移

> 图像数据现在是 JSONL `begin` 之后的 **NUS 原始字节**（ATT MTU 517，只在 `end` 做 JSONL 应答）。Android 用 Write Command（无 ATT 应答）写这些字节，每片最多 512 字节（`GATT_MAX_ATTR_LEN`）；JSONL 控制行仍走 Write Request。没有 `upload_raw` 时仍回退到 JSONL/Base64 分块。
>
> 后续：JSONL/NUS 仍作命令/元数据/进度控制面；APP→AP 数据面以后可以再换成 **ESP 通用传输组件**。AP→Tag 的 OTS/L2CAP CoC 不变。

### `transfer_image_to_tag`

需要 synchronized→updating 路径（`connect_synced` + OTS）。事件：`image_transfer_progress` / `command_result`。

请求字段：`tag`、`image_id`，以及 `image_index`（诊断/协议兼容）。**当前固件不会用该请求字段单独重定向 OTS 目标槽位**；槽位来自 `upload_image_end` 写入的 runtime descriptor。本 APP 在两条命令中发送相同的 `image_index`。找不到 `image_id` 时会失败（超时 / not-found），**不会**静默改传内置默认图。`esl_id=255` → `invalid_argument`（不支持组播）。

### Mosaico 吧唧图片命令

`list_tags` 可选返回 `display_width`、`display_height`、`display_type`。APP 仅在三者分别为
`480`、`480`、`11`（Full RGB，`0x0B`）时显示吧唧上传界面。

- `upload_badge_image_begin`：`image_id`、`slot_index`、`dest`（`ap` 或 `tag`）、`data_len=460800`、`crc32`（8 位十六进制字符串）、`width=480`、`height=480`。AP 图用 2001–2004，转发给 Tag 的图用 3001–3004，两套缓存互不共用。`begin` 应答可带 `"encoding":"raw"`。
- 有 `upload_raw` 时：`begin` 之后手机在 NUS RX 上写入恰好 460800 字节 RGB565，再发 `upload_badge_image_end`。中间不再走 JSONL 分块。
- `upload_badge_image_chunk`：仅作回退；`image_id`、`offset`、`data_b64`。
- `upload_badge_image_end`：`image_id`。`dest=ap` 只存槽，上屏靠后续切图；`dest=tag` 只写入临时转发缓冲。
- `transfer_badge_image_to_tag`：`tag`（`esl_id`、`group_id`）、`image_id`、`slot_index`；可选 `local=true` 从 AP 库存给 AP 自己显示（包括 `esl_id=254`）。发给 Tag 时从转发缓冲取图，OTS 结束后 AP 丢掉这份 RAM 副本。
- `list_badge_images`：无附加请求字段，响应只列出长期保存在 AP 上的图片。

APP 将图片居中裁剪为 480×480，并编码为逐像素小端 RGB565（低字节在前，红色高位位于后一个字节）。
CRC32 使用 IEEE reflected 算法，与 Java `CRC32` 和 ESP `esp_crc32_le` 一致。事件可复用
`image_uploaded`、`image_transfer_progress`、`command_result`，或使用对应的 badge 镜像事件。

### `cmd=unassociate`

请求：

```json
{"cmd":"unassociate","tag":{"esl_id":1,"group_id":0}}
```

`esl_id=255` → `invalid_argument`（仅 ECP，不支持组播）。

同步：`{"status":"accepted"}`  
异步：`command_result` 的 `status=ok|error`。ECP Basic State 响应、目标进入 `unassociated` 状态，或断连后发出完整且可连接的 ESL Service 广告，任一条件均可确认成功。

AP 按需连接 Tag（已同步 Tag 走 PAwR 连接流程），发送 ESL Unassociate（opcode 0x01）。ECP 下发后的断连会进入短确认窗口，不会立即判失败；确认成功后删除 AP bond 和 NVS 库存，超时/错误则保留二者以便恢复或重试。

### 后续：`read_sensor`、`factory_reset`、`get_logs`

固件 API 经 gateway 暴露后再定 schema。

---

## 事件汇总

| event | 时机 |
|-------|------|
| `scan_result` | 发现 |
| `tag_state_changed` | 生命周期 |
| `command_result` | PAwR 命令完成 / 超时 |
| `image_transfer_progress` | OTS |
| `image_uploaded` | 上传会话完成 |
| `log` | 诊断行 |
| `ap_status_changed` | start/stop 后可选推送 |
| `recovery_summary_changed` | AP 生命周期 / 健康变化 |
| `tag_recovery_changed` | 单 Tag `product_state` / 恢复字段变化 |

```json
{"event":"tag_state_changed","tag":{"esl_id":0,"group_id":0,"ble_addr":"..."},"state":"synchronized"}
{"event":"log","level":"W","msg":"PAwR timeout"}
```
