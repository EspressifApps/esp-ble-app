# ESP BLE (Android)

ESP BLE is an Android app with four BLE tools. Select a tool from the launcher after granting Bluetooth permissions.

| Tool | What it does | Compatible peer |
| --- | --- | --- |
| ESL AP Controller | Connects to an electronic shelf label AP gateway, manages Tags, sends controls, and transfers images | ESP32 `ble_esl_ap` gateway with BLE UART (NUS) and the JSONL protocol |
| Flux Data Transfer | Sends text and sample payloads over reliable Flux | BLE device with the matching Flux GATT service |
| GMP File Transfer | Sends a selected file over GMP and Flux | GMP file-transfer receiver, such as `esp_file_transfer/receiver_demo` |
| GMP OTA | Sends a firmware `.bin` file over GMP and Flux | BLE device with the matching GMP OTA service |

The peer firmware is not included in this repository. The ESL AP gateway performs PAwR communication with Tags; the phone connects to the gateway over BLE.

中文说明见 [README_CN.md](README_CN.md)。

## Requirements

- Android 12 (API 31) or later on a phone with BLE
- Android Studio, JDK 17, and Android SDK 35
- Compatible ESP peer firmware for physical-device testing

## Build

Open the project in Android Studio, or set `sdk.dir` in a local `local.properties` file and run:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. The project uses Gradle 8.11.1 through the included Wrapper.

## Use the app

1. Install the debug APK and grant the requested Bluetooth scan and connect permissions.
2. Open **ESP BLE** and choose a tool from the launcher.

### ESL AP Controller

1. With a compatible `ble_esl_ap` gateway running, open **ESL → Connect → Scan AP**, then select the gateway.
2. Use **Dashboard** for AP and Tag status, **Tags** for scanning and provisioning, **Controls** for Tag or group commands, and **Image** for image upload and transfer. **Logs** and **Settings** are also available.
3. The phone uses BLE UART (NUS) and the JSONL protocol described in [ESL AP protocol](docs/esl-ap-protocol.md). A group `command_result` means the AP queued a PAwR command; it does not confirm delivery to every Tag.

The ESL mock transport is disabled in both debug and release builds. To show **Connect Mock** for local UI testing, set `USE_MOCK_TRANSPORT` to `true` in the `debug` build type of [app/build.gradle.kts](app/build.gradle.kts), rebuild the debug APK, and open **ESL → Connect → Connect Mock**. This setting does not affect the other three tools.

### Flux Data Transfer

Open **Flux Data Transfer → Scan & Connect**, scan and connect to a compatible Flux peer, then use **Transfer** to send text or a sample payload. Use **Logs** to inspect messages. The service filter on the scan screen is optional.

### GMP File Transfer

Open **GMP File Transfer → Scan & Connect** and connect to a compatible receiver. In **Transfer**, select a file and choose **Send File**. The `esp_file_transfer/receiver_demo` in `esp-iot-solution` is a reference peer. Use **Logs** to inspect transfer messages.

### GMP OTA

Open **GMP OTA → Scan & Connect** and connect to a compatible OTA target. In **GMP OTA**, select a firmware `.bin` file and choose **Start GMP OTA Update**. Use **Logs** to inspect responses.

## Documentation

- [ESL AP protocol](docs/esl-ap-protocol.md) and [中文版](docs/esl-ap-protocol-zh-CN.md): ESL AP JSONL protocol
- [docs/flux-gmp-layering.md](docs/flux-gmp-layering.md): Flux, GMP file transfer, and GMP OTA layers

## License

This project uses the [Espressif license](LICENSE) adopted from `esp-ble-ota-android`. Its permission is limited to use on, for control of, or in conjunction with Espressif ESP8266/ESP32 products. See the full license text for the terms.
