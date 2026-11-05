# ESP BLE（Android）

ESP BLE 是一款 Android 蓝牙应用。授予蓝牙权限后，可以从启动页选择四种工具。

| 工具 | 功能 | 对端设备 | 设备端参考代码 |
| --- | --- | --- | --- |
| ESL 控制器 | 连接电子价签 AP 网关，管理 Tag、发送控制命令和传输图片 | 运行 `ble_esl_ap`、支持 BLE UART (NUS) 和 JSONL 协议的 ESP32 网关 | [BLE ESL 示例](https://github.com/espressif/esp-iot-solution/tree/master/examples/bluetooth/ble_profiles/ble_esl) |
| Flux 数据传输 | 通过可靠 Flux 协议发送文本和示例数据 | 提供匹配的 Flux GATT 服务的 BLE 设备 | [BLE SPP 服务端](https://github.com/espressif/esp-iot-solution/tree/master/examples/esp_flux/ble_spp_server) |
| GMP 文件传输 | 通过 GMP 和 Flux 发送所选文件 | GMP 文件接收端 | [文件传输接收端示例](https://github.com/espressif/esp-iot-solution/tree/master/examples/esp_file_transfer/receiver_demo) |
| GMP OTA | 通过 GMP 和 Flux 发送固件 `.bin` 文件 | 提供匹配的 GMP OTA 服务的 BLE 设备 | [BLE GMP OTA 服务端](https://github.com/espressif/esp-iot-solution/tree/master/examples/esp_gmp/gmp_ota/ble_gmp_ota_server) |

本仓库只包含 Android 应用，不包含对端固件。表中的设备端示例仅供参考，使用时需按应用协议适配。在 ESL 模式下，手机通过 BLE 连接 AP 网关，由网关负责与 Tag 进行 PAwR 通信。

English README: [README.md](README.md).

## 环境要求

- 支持 BLE、运行 Android 12（API 31）或更高版本的手机
- Android Studio、JDK 17 和 Android SDK 35
- 真机联调所需的兼容 ESP 对端固件

## 构建

使用 Android Studio 打开项目，或在本机的 `local.properties` 中设置 `sdk.dir`，然后执行：

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。项目通过仓库中的 Wrapper 使用 Gradle 8.11.1。

## 使用方法

1. 安装调试 APK，并授予应用请求的蓝牙扫描和连接权限。
2. 打开 **ESP BLE**，在启动页选择所需工具。

### ESL 控制器

1. 启动兼容的 `ble_esl_ap` 网关，进入**ESL 控制器 → 连接 → 扫描 AP**，选择网关连接。
2. 在**仪表盘**查看 AP 和 Tag 状态，在 **Tags** 页扫描和配网，在**控制**页向 Tag 或组发送命令，在**图片**页上传和传输图片。还可以使用**日志**和**设置**页面。
3. 手机与网关使用 BLE UART (NUS) 和 JSONL 协议，详见 [ESL AP 协议](docs/esl-ap-protocol-zh-CN.md)。组播 `command_result` 表示 AP 已将 PAwR 命令排入队列，不代表每个 Tag 都已收到。

ESL 的 Mock 传输在 debug 和 release 构建中默认关闭。如需显示**连 Mock**入口进行本地界面调试，请在 [app/build.gradle.kts](app/build.gradle.kts) 的 `debug` 构建类型中将 `USE_MOCK_TRANSPORT` 设为 `true`，重新构建调试 APK，然后进入**ESL 控制器 → 连接 → 连 Mock**。该设置不影响另外三个工具。

### Flux 数据传输

进入 **Flux 数据传输 → 扫描与连接**，扫描并连接兼容的 Flux 设备，然后在**数据传输**页发送文本或示例数据。**日志**页可查看消息；扫描页的服务过滤开关可按需启用。

### GMP 文件传输

进入 **GMP 文件传输 → 扫描与连接**，连接兼容的接收端。在**数据传输**页选择文件并点击**发送文件**。**日志**页可查看传输消息。

### GMP OTA

进入 **GMP OTA → 扫描与连接**，连接兼容的 OTA 设备。在 **GMP OTA** 页选择固件 `.bin` 文件，点击 **Start GMP OTA Update**。**日志**页可查看响应消息。

## 文档

- [ESL AP 协议](docs/esl-ap-protocol-zh-CN.md) 和 [English](docs/esl-ap-protocol.md)：ESL AP JSONL 协议
- [docs/flux-gmp-layering.md](docs/flux-gmp-layering.md)：Flux、GMP 文件传输和 GMP OTA 的协议分层

## 许可证

本项目沿用 `esp-ble-ota-android` 的[乐鑫许可证](LICENSE)。其授权仅限于在乐鑫 ESP8266/ESP32 产品上使用、控制这些产品，或与这些产品配合使用；具体条款请以许可证全文为准。
