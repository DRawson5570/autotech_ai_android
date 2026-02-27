# Autotech Gateway - Android App

Android companion app for the Autotech AI diagnostic gateway. Connects to ELM327/OBD-II adapters via Bluetooth, BLE, or WiFi and relays vehicle data to the Autotech AI server.

## Features

- **Multi-connection support**: Bluetooth Classic (SPP), BLE, and WiFi
- **Live diagnostics**: Real-time PID monitoring with gauges
- **DTC reading/clearing**: Read and clear diagnostic trouble codes
- **VIN decoding**: Automatic VIN reading and vehicle identification
- **Module discovery**: Scan all ECU modules (HS-CAN + MS-CAN for Ford)
- **UDS DID reading**: Read manufacturer-specific data identifiers
- **Server relay**: WebSocket tunnel to Autotech AI production server
- **Foreground service**: Maintains persistent connection in background

## Architecture

```
┌──────────────┐   BT/BLE/WiFi   ┌──────────────────┐
│  ELM327      │◄───────────────►│  ElmConnection    │
│  Adapter     │                  │  (transport)      │
└──────────────┘                  └────────┬──────────┘
                                           │
                                  ┌────────▼──────────┐
                                  │  OBDProtocol       │
                                  │  (OBD/UDS framing) │
                                  └────────┬──────────┘
                                           │
                                  ┌────────▼──────────┐
                                  │  GatewayService    │
                                  │  (Android Service) │
                                  └────────┬──────────┘
                                           │
                          ┌────────────────┼────────────────┐
                          │                │                │
                 ┌────────▼─────┐ ┌───────▼──────┐ ┌──────▼───────┐
                 │  Compose UI  │ │  ServerTunnel │ │  Session DB  │
                 │  (local)     │ │  (WebSocket)  │ │  (Room)      │
                 └──────────────┘ └──────────────┘ └──────────────┘
```

## Building

### GitHub Actions (CI)

Push a version tag to trigger the build:

```bash
git tag v1.0.1
git push origin v1.0.1
```

This builds debug + release APKs and creates a GitHub Release with the artifacts. You can also trigger a build manually from the Actions tab.

**APK signing** (optional): Add these repo secrets for signed release builds:
- `KEYSTORE_BASE64` — base64-encoded `.jks` keystore
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

### Local build (without Android Studio)

```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Requirements

- Android 8.0+ (API 26)
- Bluetooth permissions (for BT/BLE adapters)
- Location permission (required for BT scanning on Android)
