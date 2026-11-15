<!-- UNTESTED ON HARDWARE — not part of the demo build -->
# LoRa bridge (Meshtastic radio over USB serial)

**Untested on hardware. Not part of the demo build.** No radio was on hand; nothing here has been flashed, wired or run against a real Meshtastic device. Only the pure-JVM self-test below has run.

`src/MeshtasticTransport.kt` implements the core `Transport` interface (`core/src/main/kotlin/mesh/core/Api.kt`). A desktop node (or, later, the Android app over USB OTG) adds it next to its LAN or BLE transport, and a Meshtastic radio becomes a long-range link that carries **signed public posts only**.

## What crosses, what does not

| Frame | Over LoRa | Why |
|---|---|---|
| ENVELOPE, type POST | yes | Public and signed; the receiving core checks signature and PoW as on any link. |
| HELLO, HELLO_AUTH | yes | Without them the core never authenticates the link and drops everything else. No message content. |
| SYNC_OFFER | rewritten to an empty offer, at most one per 30 s per link | Keepalive for the core's 45 s silent-link check. The real offer lists message ids, DM ids included: metadata, and too much airtime. |
| DM, ACK, PRESENCE, SYNC_WANT, IHAVE, GRAFT, PRUNE, BAN_PROOF | no | DMs never leave the phone mesh. PRESENCE every 30 s from every node would eat the duty cycle. |

The same allowlist runs on receive, so a radio peer cannot push DMs in through the bridge. This filter is about airtime and metadata, not trust: the core still verifies every frame.

Consequences, plainly:
- Contacts beyond the radio do not appear in the contact list (no PRESENCE), and DMs are not routed over it. A DM whose only path is LoRa fails, as designed.
- Lost posts are not recovered over LoRa (no sync). They still reach the far side later through any other path or a data-mule bundle.
- Assumes the shipped `BroadcastMode.FLOOD`. In `TREE` mode a link can go lazy and would then send only IHAVE, which is dropped.

## Wire format

- Meshtastic client API stream framing: `0x94 0xC3`, 2-byte big-endian length (at most 512), then a protobuf `ToRadio` (to the radio) or `FromRadio` (from it). Debug text the radio prints between frames is skipped. Start: 32 bytes of `0xC3` (wake and resync, as the official Python client does), then `want_config_id`. A heartbeat every 60 s.
- Our bytes go in `MeshPacket.decoded` (`Data`) with `portnum = PRIVATE_APP` (256), `hop_limit = 3`, no `want_ack`.
- Each payload: `'O'`, kind (0 beacon, 1 chunk), u16 sequence, u8 index, u8 count, then up to 224 bytes. `DATA_PAYLOAD_LEN` is 233; we stay at 230.
- Chunking: up to 8 chunks, so frames up to 1,792 bytes. A post envelope with the full 1,000-byte body is about 1.2 KB (6 chunks). Bigger frames are dropped, not queued. Partial frames: at most 16 pending, dropped after 60 s.
- Links: each remote bridge (Meshtastic node number) is one link, `lora!xxxxxxxx`, sent as unicast. A bridge broadcasts a 6-byte beacon every 5 minutes so others find it. A post is sent once per remote bridge, so keep to one or two bridges per radio mesh.
- Protobuf field numbers are hand-coded from `mesh.proto` / `portnums.proto` (comments next to each). Check them against the current `.proto` before first use. We do not copy the `.proto` files (the Meshtastic firmware is GPL-3.0; see research notes on licence).

## Limits (from the research, `research/ADVANCES.md` 2.7)

- 233-byte payload per packet at a default 1.07 kbps (Long Fast). One full chunk is about 1.9 s on air with our rough estimate (payload + 26 bytes, preamble ignored); a full 1,000-byte post is about 11 s per hop. Short text posts only. Photos, bundles and the APK are out.
- Duty cycle: the transport keeps its own rolling one-hour airtime budget (default 10 %, the EU 868 MHz figure; Meshtastic's US page states none). Over budget, `send` returns false and the core treats that copy as not sent. The firmware enforces its own regional limit too.
- Meshtastic channel traffic is unauthenticated by default and the default key (`AQ==`) is public. That does not matter here because every envelope is signed end to end, and posts are public anyway. Anyone on the channel can read the posts.

## Wiring and usage

1. A Meshtastic radio (for example a Heltec WiFi LoRa 32 V3/V4, about $18-23) with current firmware, region set (`meshtastic --set lora.region US`), on the default channel or a shared private one. Two radios to test.
2. USB cable to the computer running the desktop node. It shows up as `/dev/ttyUSB0` or `/dev/ttyACM0` (Linux), `COMx` (Windows). Linux: add the user to `dialout`.
3. Dependency (not in any build): `com.fazecast:jSerialComm:2.11.0` (or any 2.x). `src/JSerialComm.kt` opens the port at 115200 8N1.
4. In `desktop/src/main/kotlin/mesh/desktop/Main.kt`, add it to the transport list: `MeshNode(args.data, ExecutorScheduler(), listOf(lan, web, MeshtasticTransport({ openSerial("/dev/ttyUSB0") })))`, and add the two source files and the jSerialComm dependency to that module. Not wired today.
5. Only one program may own the serial port: close the Meshtastic app or CLI first.

## Self-test (no radio)

`test/SelfTest.kt` is a plain `main()` with checks (no JUnit): stream framing with junk and oversized lengths, the allowlist, chunking and reassembly (out of order, expiry, malformed headers), two bridges through a fake radio (post crosses intact, DM and real offers do not, offers become one keepalive), inbound DM dropped, and the duty-cycle refusal.

```
extras/lora-bridge/run-selftest.sh     # from the repo root; prints "lora-bridge self-test: 28 checks passed"
```

It builds `core.jar`, compiles the two files with the Kotlin 2.0.21 compiler from the Gradle cache (run `./gradlew build` once first) and runs the checks. `JSerialComm.kt` is not compiled there because it needs the jSerialComm jar.
