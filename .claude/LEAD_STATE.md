# Lead state (OfflineMesh build). Update this file as work moves.

## Where things are (2026-11-14 13:50 CT)
- Repo root = this folder. GitHub: KeshavAditya/offlinemesh (private), branch main. Part 3 files committed verbatim, plus .gitattributes (LF endings).
- Laptop ready: scripts/setup-check.sh all OK. jq at %LOCALAPPDATA%/Microsoft/WinGet/Packages/jqlang.jq_*/ (on user PATH for new shells).
  sdkmanager/avdmanager installed in $ANDROID_HOME/cmdline-tools/latest/bin with bash wrappers. ~/.bashrc has the guide's ANDROID_HOME/PATH lines.
- AVDs meshA/meshB/meshC (pixel_8, system-images;android-36;google_apis_playstore;x86_64). WHPX usable. No real phones connected.
- Installed: platform android-36, build-tools 35/36, Gradle 8.14.3 in ~/.gradle cache, JDK 17.0.7 (Adoptium), Android Studio 2025.1 (build 251).
- Deadline Sun Nov 15 11:00 AM CT, feature freeze 8:00 AM.

## Owner decisions (asked once up front; NO questions during the build)
1. This session leads; helper agents build. Use subagent_type mesh-core / mesh-ui / mesh-android / mesh-qa.
   Give each helper ONE small goal (a step from section 8/9), not a whole workstream. Parallel helpers use isolation "worktree"
   (stands in for the guide's om-* clones) and push to main with `git pull --rebase origin main` then `git push origin HEAD:main`.
2. Private repo, push often.
3. No phones: verify on 3 emulators + 3 desktop nodes. A passing emulator run of e2e.sh counts as a PROVISIONAL Gate 3.
   Then build stretch items 1-5 in order (section 2). At the end, give the owner a checklist for the real-phone steps (10.4, acceptance on phones).
4. Ponytail plugin installed by the owner before this session. Graphify at the very end (pip install graphifyy && graphify install, then /graphify . in the main folder -> graphify-out/graph.html).

## Order (section 9)
P1 skeleton+stubs (core) -> P2 core/sim (core) || BLE spike on emulators (android) || UI on fake node (ui) -> Gate 1
-> P3 lan/desktop/run-3-desktop/e2e (ui) -> Gate 2 -> P4 Android app on emulators -> provisional Gate 3
-> P5 redteam/persistence/e2e on emulators -> stretch 1-5 -> docs (qa: THREATS, DEMO, NUMBERS, DEVPOST) -> graphify -> final report.
Spike rule: if BLE L2CAP fails on emulators (unverified per 10.3a), note it in STATUS.md and keep going; don't block on it.

## Progress
- [x] Setup (Parts 1-3)
- [x] P1  - [x] P2/Gate1  - [x] P3/Gate2  - [x] P4/Gate3(prov)  - [ ] P5  - [ ] Stretch 1-5  - [ ] Docs  - [ ] Graphify  - [ ] Final report

## Rules in force
- Owner's global CLAUDE.md token rules: small helper goals, helper handoff at ~200k, quiet builds (output to file, read failures only).
- Project CLAUDE.md + Part 5 of the guide win on what to build. Append to STATUS.md after each task.
- When everything is done: tell the owner it's complete, with results and the real-phone checklist.

## Log
- 2026-11-14 ~14:00 lead started. P1 helper (mesh-core) launched on main directly (sole helper). Next: P2 = core helpers in sequence (codec/crypto/pow/identity -> security/router/sync/store/node -> sim tests), in parallel android spike (worktree) + UI on fake node (worktree).
- 14:20 P1 done (cf539b5..7d9d596): AGP 8.11.0, Kotlin 2.0.21, Ktor 3.0.3, kx-ser 1.7.3. Stubs core/src/main/kotlin/mesh/core/Api.kt. Launching P2: core-1 (constants/identity/codec/crypto/pow), android spike, UI fake node — all worktrees.
- 14:25 P2 launched: core-1 (identity/codec/crypto/pow+10.1), android BLE spike (emulators meshA/B), ui (UiServer+index.html on fake node). All worktrees. Next core helpers: core-2 security/router/sync/store/node, core-3 SimNetwork + 10.2 tests.
- 14:35 core-1 done 5aed3cb (P-256 JDK crypto, 11 unit tests). core-2 launched: security/router/sync/store/node + SimNetwork + 10.2 #1-4,8. core-3 next: red-team 5.12 + 10.2 #5-7,9-11.
- QUEUED for core-3: Identity.kt save() — set tmp file owner-only (setReadable/Writable(false,false) then (true,true)) before writing key (security review finding).
- 14:50 ui-1 done 6c09f58: UiServer (UiNode iface in uiserver, Host/Origin loopback check), index.html, runFake task. ui-2 launched: LanTransport + loopback test. Next ui-3 (after core-2): desktop main + run-3-desktop.sh + e2e.sh. Later: guest.html/8090 (stretch 1).
- QUEUED for qa/THREATS.md limitations: loopback UI API is unauthenticated by design (e2e/adb forward); other local apps on same device can drive it; browser pages blocked by Host/Origin check.
- 15:05 ui-2 done 6566fea: LanTransport(listenPort, staticPeers, discoveryPrefix?); static peer on ONE side only per pair (else 2 links); discovery untested. Waiting core-2 before ui-3 desktop/scripts.
- 15:08 ui-3 launched: desktop Main + run-3-desktop.sh + e2e.sh (will run e2e once core-2 lands).
- 15:25 android spike-1 blocked on adb "unauthorized": FIX = cold boot (`emulator -avd X -no-snapshot-load -no-snapshot-save`); meshA now authorized. Spike code b10b177 (SpikeActivity, BleSpike.kt, UUID 6d657368-6e65-7400-8000-00805f9b34fb). Relaunching spike-2.
- QUEUED android: SpikeActivity exported=true -> remove or exported=false in app build step.
- QUEUED lan: cap discovery dialer threads (check links.size/MAX_LINKS before thread); core must close links with no HELLO within HELLO_TIMEOUT (verify in core-3).
- 15:45 spike-2 WORKS on emulators (f4e389e): advertise/scan/L2CAP/text both ways. Gate1 BLE half met. android-2 launched: BleL2capTransport + permissions/manifest. android-3 next: MeshService + WebView + settings row + deploy/forward scripts (+ remove/unexport SpikeActivity).
- 16:00 core-2 done 6bf366a: MeshNode/Security/Router/Store real, 17 tests (10.2 #1-4,8). GATE 1 MET. core-3 launched: attacks FLOOD/SPOOF/REPLAY, 10.2 #5-7,9-11, Identity perms, HELLO timeout, 5.10/5.11 gaps.
- QUEUED qa/THREATS.md: route hijack via unsigned hop count (5.7 reverse path) = known limitation. Sent core-3: replay-after-SEEN_CAP test, contacts cap.
- 16:30 android-2 done 5361ce5: BleL2capTransport(ctx,prefix4), Perms, manifest; spike removed; 59,990B frames OK on emulators. Sent core-3: idle-link timeout 45s. android-3 launched: MeshService+WebView+settings row+deploy-all/forward-ui; leaves 3 emulators running.
- 16:45 core-3 done d4263fb: 26 tests, all 10.2; #9 = 41 tx / 40 links (bound 80), 25 nodes, seed 42. Fixed replay-after-SEEN_CAP (real bug), contacts cap 256, Identity perms, HELLO timeout test. Pending: idle-link timeout msg (should resume). Open: routes map uncapped; own sent DMs '(sent before restart)'.
- 17:00 ui-3 built desktop+scripts (06f0155) — e2e items 1-4,6 pass on desktop, 5 waited for attack() (now in). It ALSO built stretch 1 guest (496dd89, GuestServer 8090 + guest.html) early. Sent: rerun e2e (Gate 2) + 3 guest security fixes (PIN lockout, body cap, guest label order). Sent core-3: wanted-set leak + contacts refuse-when-full + idle timeout.
- 17:10 core fixes done 7a83506: 27 tests; silent-link close 45s (muted links exempt; liveness from PRESENCE 30s), wanted-set leak fixed, contacts refuse-when-full. Core workstream COMPLETE.
- 17:30 GATE 2 PASSED (desktop e2e all 6, twice, 90bdb66). Guest fixes in. Lead fixed contacts aging 7aecadf. STATUS.md now merge=union. Owner TODO later: record Gate 2 backup video (can't do from here). Waiting: android-3 (service/WebView/3 emulators), qa redteam tests.
- 17:55 qa redteam done e1e9abc (16 tests). Lead fixed SYNC_WANT bugs -> 43 core tests, 0 skipped. QUEUED THREATS.md: HELLO_AUTH relay/MITM binding (sigs still hold). Guest PIN hardened by lead 1c8f0c3.
- 18:05 lead: per-link wantServed set (SYNC_WANT not dependent on LRU). Sent android-3: readiness via service signal not TCP probe (port squat). THREATS.md: unauth loopback UI (by design).
- 18:15 PROVISIONAL GATE 3 PASSED: e2e all 6 on 3 emulators over BLE (f21a4c3). Serial map: emulator-5554=A/8081, 5556=B/8082, 5558=C/8083. Emulator start: meshA first, wait for %TEMP%/netsim.ini, then B, C; only one netsimd.exe. android-3 resumed for readiness-signal fix.
- Stretch plan: 1 guest (uiserver done; app toggle+hotspot IP+QR pending -> android), 2 photo DM (ui), 3 notif inline reply (android), 4 ban-proof gossip (core), 5 QR identity verify (ui; QR also used by guest). Launching core-ban-gossip + ui-photo now.
- P5 leftovers: persistence restart check on emulators; plaintext-in-B-log check on phones.
- 18:25 ui-3 final: Gate 2 confirmed (2 runs). NOTE desktop run-3 uses 8081-8083 = same as emulator adb forwards; stop forwards (adb forward --remove-all) before desktop e2e. Desktop e2e rerun on latest core still TODO (do at end).
- 18:40 stretch 4 DONE 942406e: BAN_PROOF frame 0x14 (two POSTs), verify-before-ban, relay once; guide 5.2/5.6 edited; 46 core tests. Queue: stretch 5 QR (ui, after photo helper frees index.html); stretch 1 app side + stretch 3 notif (android, after android-3).
- 18:55 stretch 2 DONE 52e5bb5 (sendDmImage, Msg.img, guide s6 edited). App needs WebChromeClient.onShowFileChooser. ui-qr launched (stretch 5 + guest QR in Network tab). Next android-4: guest toggle+hotspot IP+guestInfo feed+file chooser; android-5: notifications w/ inline reply.
- 19:10 stretch 5 DONE e6567c6: zxing /api/qr, /api/guest; UiServer(node, port, guest: () -> GuestServer? = {null}). Launching qa-docs (THREATS, DEMO, NUMBERS, DEVPOST).
- 19:35 qa-docs DONE 47ea3ba: docs/THREATS,DEMO,NUMBERS,DEVPOST + PowTimingTest, BroadcastCostTest. Found core bug: 50-node honest mesh trips LINK_FLOOD (sender exceeds receiver bucket). core-pacing helper launched (unskip fiftyHonestNodesTripNoLinkFlood). PoW: post 6-9 ms, max DM 0.6-1.2 s (laptop).
- 19:55 core pacing DONE 6645127: per-link out bucket 25/10s, queue 256; 50-node clean; 51 tests 0 skipped.
- 20:10 android-3 DONE af7ba85 (readiness via ui.start(), BindException swallow shortcut), 27b0f21 (BLE dial key back to prefix — address rotates on emulators; 30 s redial pause after quick close). Lead pushed deploy-all --fresh b820258. e2e on emulators: 6/6 with fresh data. Launching android-4: guest toggle+hotspot IP+UiServer guest feed + WebView file chooser. Then android-5 notifications (stretch 3).
