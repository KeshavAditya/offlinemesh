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
[core] [14:21] done: red-team hooks + all 10.2 tests (#9: 41 transmissions / 40 links, bound 80); replay-after-LRU dedupe, contacts cap 256, hello-timeout test, key tmp owner-only
[ui] [14:22] done: 5.13 guest: mesh.ui.GuestServer(node, 8090) on 0.0.0.0 (pin, urls() for QR/text, /api/feed, /api/guest-post, 403 wrong PIN, 1 post/5 s/IP) + guest.html; index.html shows 'guest of B'. app: start/stop it from the Guest mode toggle and show urls() + pin
[core] [14:24] done: idle-link close (45 s silent, muted exempt), SYNC_WANT leak fix, contacts table full refuses new unverified; 27 core tests green
[ui] [14:27] done: Gate 2: run-3-desktop.sh + e2e.sh 8081 8082 8083 exit 0, all 6 items PASS (twice, core d4263fb). GuestServer hardened: per-IP (5) + global (20, then PIN rotates, PinGuard.onRotate) wrong-PIN limits, body cap 4096 (Content-Length + bounded read), feed shows signer first 'B [guest: Zoe]: ...'
[lead] [14:28] done: contacts table recovers (silent unverified contacts expire after 10 presence intervals when full)
[lead] [14:30] done: guest PinGuard hardening (expiring per-IP windows, kept across rotation, off after 3 rotations)
[qa] [14:32] done: 16 redteam tests in core/src/test/kotlin/mesh/redteam/RedTeamTest.kt (2 bugs found, @Ignore'd: onWant answers duplicate ids in one SYNC_WANT, 512 copies per frame; SYNC_WANT not rate limited and resends envelopes the link already has) -> mesh-core
[lead] [14:34] done: fixed redteam SYNC_WANT amplification bugs; core 43 tests green, 0 skipped
[android] [14:37] done: MeshService + WebView + deploy/forward (3 emulators meshA/B/C = emulator-5554/5556/5558 -> 8081/8082/8083: full BLE mesh, 2 authed links each; WebView UI ok, survives rotation; LAN toggle on/off ok; e2e.sh items 1-6 PASS). Emulator gotcha: start meshA, wait for %TEMP%/netsim.ini, then B and C, else a 2nd netsimd isolates one emulator's Bluetooth
[ui] [14:40] done: run-3-desktop.sh refuses busy ports and checks each port answers with its own node id. Rerun on core after 14:24 not done: 8081-8083 held by another run (ids 750c/95ef/37d4); my e2e briefly drove those nodes (named A/B/C, A-C left muted)
[core] [14:43] done: stretch 4 ban-proof gossip (new frame 0x14 BAN_PROOF; guide 5.2 table and 5.6 POST rule edited; section 6 unchanged; core 46 tests green)
[ui] [14:46] done: stretch 2 photo DM: {"t":"dm","to","img":<base64 JPEG, 39,999 B max>} cmd + Msg.img + MeshNode.sendDmImage (section 6 extended in same commit); kind-1 DM, FF D8 FF checked in Cmd, core send+receive and page; page downscales via off-screen canvas. Desktop A->B->C: bytes identical, DELIVERED 2 hops, browser 822 KB -> 36 KB. Note app/: WebView needs onShowFileChooser for the file input
[ui] [14:54] done: stretch 5 QR identity + guest QR (zxing core 3.5.3, GET /api/qr SVG + GET /api/guest, guide section 6 line added; app: UiServer(node, port) { guestServerOrNull }; :uiserver:test + :app:assembleDebug green)
[qa] [15:05] blocked->mesh-core: 50 honest sim nodes log 27 LINK_FLOOD + 2 LINK_CLOSED, 46/49 get a post (start-up PRESENCE flood and link-up replay of up to 64 presences go through the 30/10 s link bucket); @Ignore'd test redteam/BroadcastCostTest.fiftyHonestNodesTripNoLinkFlood. Also 10.2 #9 count is 41 or 42 run to run (jitter not seeded)
[qa] [15:05] done: THREATS/DEMO/NUMBERS/DEVPOST in docs/ (+ redteam PowTimingTest, BroadcastCostTest; core 51 tests, 1 ignored)
[core] [15:09] done: sender-side link pacing; 50-node honest mesh clean
