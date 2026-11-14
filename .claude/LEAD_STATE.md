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
- [x] P1  - [ ] P2/Gate1  - [ ] P3/Gate2  - [ ] P4/Gate3(prov)  - [ ] P5  - [ ] Stretch 1-5  - [ ] Docs  - [ ] Graphify  - [ ] Final report

## Rules in force
- Owner's global CLAUDE.md token rules: small helper goals, helper handoff at ~200k, quiet builds (output to file, read failures only).
- Project CLAUDE.md + Part 5 of the guide win on what to build. Append to STATUS.md after each task.
- When everything is done: tell the owner it's complete, with results and the real-phone checklist.

## Log
- 2026-11-14 ~14:00 lead started. P1 helper (mesh-core) launched on main directly (sole helper). Next: P2 = core helpers in sequence (codec/crypto/pow/identity -> security/router/sync/store/node -> sim tests), in parallel android spike (worktree) + UI on fake node (worktree).
- 14:20 P1 done (cf539b5..7d9d596): AGP 8.11.0, Kotlin 2.0.21, Ktor 3.0.3, kx-ser 1.7.3. Stubs core/src/main/kotlin/mesh/core/Api.kt. Launching P2: core-1 (constants/identity/codec/crypto/pow), android spike, UI fake node — all worktrees.
