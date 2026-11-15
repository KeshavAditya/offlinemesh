# OfflineMesh

Secure messaging for phones with no internet and no server. Phones pass messages to each other over Bluetooth LE (or a LAN), and every relay is assumed to be a stranger.

**Not audited. Do not rely on it where your safety depends on it.** This is a hackathon build. Read `docs/THREATS.md` for what it does and does not protect against.

## What it does

- Direct messages encrypted end to end (ECDH + AES-256-GCM) and padded to a size bucket. Relays cannot read them.
- Every message signed; the node ID is a hash of the public key. A changed message is rejected at the next honest phone.
- Public posts limited to 3 per 10 seconds per key, with cryptographic proof and a ban for cheaters. Link scoring closes misbehaving links.
- DMs are routed, not flooded, with a backup route if a relay drops them. Group chats up to 16 people.
- Panic wipe, impersonation warning, QR hop for short messages, mission profiles, photo DMs.
- Android app (R8 release APK about 2.4 MB) and a desktop node over TCP.
- Browser guests (for example an iPhone) join through the host phone's hotspot and run a full web client in the page: their own key, signed posts, encrypted DMs. They cannot relay. The host can also hand out the APK offline.

## Build and test

```
./gradlew test                       # 146 tests (core 121 incl. 29 red-team, uiserver 16, app 4, lan 3, desktop 2)
./gradlew :app:assembleRelease       # Android APK, R8-shrunk
./gradlew :core:bench                # forwarding benchmark, writes docs/NUMBERS.md
scripts/run-3-desktop.sh             # three desktop nodes A <-> B <-> C, UIs on 8081..8083
scripts/e2e.sh 8081 8082 8083        # acceptance checks against running nodes
scripts/deploy-all.sh                # install and launch on every adb device
```

## Docs

- `docs/THREATS.md`: threat model and honest limits.
- `docs/NUMBERS.md`: every measured figure, and where it was measured (simulator, emulator, real phone).
- `docs/DEMO.md`: demo script, fallback plan and booth setup.
- `docs/DEVPOST.md`: project write-up.
- `OFFLINEMESH_COMPLETE_GUIDE.md`: the full specification.

## Extras (untested on hardware)

Code for hardware we did not have, in `extras/`. Not in any Gradle build, not part of the demo, never run on a device.

- `extras/portal-box/`: captive-portal Wi-Fi for guests. ESP32 sketch (serves the web client, cannot host the mesh) and a Raspberry Pi setup script (hostapd + captive dnsmasq + desktop node as a service).
- `extras/lora-bridge/`: a `Transport` over a Meshtastic radio's serial API that bridges signed public posts only. Its framing, chunking and filter have a self-test that runs without a radio (`extras/lora-bridge/run-selftest.sh`).

## Status

The simulator, desktop nodes and four Android emulators pass all eight acceptance checks. One run on two real phones plus a laptop passed six of seven checks; the seventh failed because the phone's battery saving stopped the app. Details in `docs/NUMBERS.md`.
