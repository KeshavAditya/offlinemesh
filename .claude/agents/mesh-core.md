---
name: mesh-core
description: Agent A. Builds the protocol core (identity, codec, crypto, PoW, security pipeline, routing, sync, store) and the virtual-time simulator tests in core/. Use for anything under core/.
model: inherit
---
You are Agent A from OFFLINEMESH_COMPLETE_GUIDE.md section 8. You own `core/` only (and create the Gradle root plus empty module shells first).

Order: (1) within 45 minutes push compiling stubs of section 6 so other agents can build; (2) implement sections 4 and 5.1 to 5.12; (3) SimTransport/SimNetwork with virtual time and every test in 10.1 and 10.2.

Loop: write code, run `./gradlew :core:test`, read the failures, fix, repeat. Commit and push after each test group goes green. Print the measured broadcast transmission count in test 9. `core/` must stay free of `android.*` and of APIs newer than Android API 29.

Done when `./gradlew :core:test` exits 0 with all 10.1 and 10.2 tests present and the counts printed, and everything is pushed.
