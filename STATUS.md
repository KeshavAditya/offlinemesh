# STATUS
[core] [13:56] done: P1 skeleton + section 6 stubs (./gradlew build green; AGP 8.11.0, Kotlin 2.0.21, Ktor 3.0.3, serialization 1.7.3)
[core] [14:01] done: identity/codec/crypto/pow + 10.1 tests (11 green)
[ui] [14:03] done: UiServer + index.html on fake node (./gradlew :uiserver:runFake --args=8091)
[ui] [14:05] done: LanTransport + loopback test
[android] [14:06] blocked: BLE spike on emulators: spike builds (SpikeActivity); meshA/meshB booted but adb 'unauthorized' (playstore image shows Allow USB debugging dialog); human must tick 'Always allow' + Allow in each emulator window, then rerun spike
[android] [14:10] done: BLE spike on emulators: works (API 36, netsim): advertise ok psm=128, scan found peer, insecure L2CAP connect attempt=0, 'hello' exchanged both ways in ~1s; no code changes. Next: real phones
[core] [14:12] done: node/security/router/sync/store + 10.2 #1-4,8 (mesh.core.ExecutorScheduler available for app/desktop)
[ui] [14:20] blocked: Gate 2 e2e: run-3-desktop.sh + e2e.sh pass items 1,2,3,4,6 on real core; item 5 fails because MeshNode.attack() is a no-op (core: implement 5.12 flood/spoof/replay). Note: curl localhost:8081 can hit another local server over IPv6; use 127.0.0.1
[android] [14:20] done: BleL2capTransport + permissions (2 emulators: 3 small + 59,990 B frame both ways, CRC match, ~5 s; close -> onLinkDown both sides, redial <1 s; BT toggle -> new PSM re-advertised, relink ~4 s. Note: netsim BT stack once SIGABRT on HCI LE_ADV_FILTER timeout, emulator-only; peer saw no link-down after that crash, so core must detect silent links)
