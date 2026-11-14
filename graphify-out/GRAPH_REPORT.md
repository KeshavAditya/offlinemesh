# Graph Report - madhacks project  (2026-11-14)

## Corpus Check
- 62 files · ~46,362 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 9 file(s) not represented in the graph (top: (none) 2, .xml 2, .properties 2)

## Summary
- 853 nodes · 1895 edges · 55 communities (25 shown, 30 thin omitted)
- Extraction: 97% EXTRACTED · 3% INFERRED · 0% AMBIGUOUS · INFERRED: 58 edges (avg confidence: 0.88)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `ee26e813`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- MeshNode
- UnitTest.kt
- GuestServerTest.kt
- Crypto
- Part 5. The build specification
- .decodeFrame
- MainActivity.kt
- RedTeamTest
- MeshService.kt
- Part 3. Files to create in the repo
- Cmd
- Pow
- LanTransport.kt
- Guard
- SimTest
- e2e.sh
- BleL2capTransport.kt
- BleL2capTransport
- LinkId
- Sim.kt
- Rec
- .start
- OfflineMesh (Devpost draft)
- ApiSmokeTest.kt
- OfflineMesh demo
- GuestServer
- Lead state (OfflineMesh build). Update this file as work moves.
- OfflineMesh threat model
- OfflineMesh: project instructions for Claude Code
- settings.json
- QrTest
- Transport
- UiServer
- run-3-desktop.sh
- setup-check.sh
- deploy-all.sh
- forward-ui.sh
- stop-check.sh
- STATUS.md

## God Nodes (most connected - your core abstractions)
1. `MeshNode` - 83 edges
2. `Envelope` - 42 edges
3. `Link` - 29 edges
4. `LinkId` - 27 edges
5. `RedTeamTest` - 26 edges
6. `BleL2capTransport` - 21 edges
7. `Codec` - 21 edges
8. `Crypto` - 19 edges
9. `Identity` - 19 edges
10. `SimTest` - 17 edges

## Surprising Connections (you probably didn't know these)
- `Agent C: UI, server, desktop. Owns `uiserver/`, `lan/`, `desktop/`, `scripts/run-3-desktop.sh`, `scripts/e2e.sh`` --references--> `MeshNode`  [INFERRED]
  OFFLINEMESH_COMPLETE_GUIDE.md → core/src/main/kotlin/mesh/core/MeshNode.kt
- `Agent B: Android. Owns `app/` and `scripts/deploy-all.sh`, `scripts/forward-ui.sh`` --references--> `BleL2capTransport`  [INFERRED]
  OFFLINEMESH_COMPLETE_GUIDE.md → app/src/main/java/com/meshnet/offline/BleL2capTransport.kt
- `Agent B: Android. Owns `app/` and `scripts/deploy-all.sh`, `scripts/forward-ui.sh`` --references--> `MainActivity`  [INFERRED]
  OFFLINEMESH_COMPLETE_GUIDE.md → app/src/main/java/com/meshnet/offline/MainActivity.kt
- `Agent B: Android. Owns `app/` and `scripts/deploy-all.sh`, `scripts/forward-ui.sh`` --references--> `Transport`  [INFERRED]
  OFFLINEMESH_COMPLETE_GUIDE.md → core/src/main/kotlin/mesh/core/Api.kt
- `10.2 Simulator tests (real `MeshNode`s on `SimTransport`, virtual time)` --references--> `MessageAdded`  [INFERRED]
  OFFLINEMESH_COMPLETE_GUIDE.md → core/src/main/kotlin/mesh/core/Api.kt

## Import Cycles
- None detected.

## Communities (55 total, 30 thin omitted)

### Community 0 - "MeshNode"
Cohesion: 0.10
Nodes (8): Envelope, MeshNode, TransportListener, Peer, Bucket, Link, Out, exec()

### Community 1 - "UnitTest.kt"
Cohesion: 0.05
Nodes (9): C, ExecutorScheduler, Stored, BroadcastCostTest, Run, Args, main(), parseArgs() (+1 more)

### Community 2 - "GuestServerTest.kt"
Cohesion: 0.06
Nodes (33): Attack, FLOOD, REPLAY, SPOOF, Contact, DebugState, LinkInfo, MeshEvent (+25 more)

### Community 3 - "Crypto"
Cohesion: 0.10
Nodes (3): Crypto, PhotoDmTest, UnitTest

### Community 4 - "Part 5. The build specification"
Cohesion: 0.04
Nodes (45): 0. How to use this file, 10.1 Unit tests (`core`), 10.2 Simulator tests (real `MeshNode`s on `SimTransport`, virtual time), 10.3 On phones (no MCP servers needed), 10.3a Three emulated phones on the laptop (before and alongside real phones), 10.4 The team's phones, 10. Testing, 11. Demo script and judge questions (+37 more)

### Community 5 - ".decodeFrame"
Cohesion: 0.12
Nodes (15): Bad, BanProof, Codec, Env, Frame, Hello, HelloAuth, Reader (+7 more)

### Community 6 - "MainActivity.kt"
Cohesion: 0.08
Nodes (3): MainActivity, WebChromeClient, Perms

### Community 9 - "MeshService.kt"
Cohesion: 0.09
Nodes (4): DmNotifier, ReplyReceiver, MeshService, Agent B: Android. Owns `app/` and `scripts/deploy-all.sh`, `scripts/forward-ui.sh`

### Community 10 - "Part 3. Files to create in the repo"
Cohesion: 0.07
Nodes (27): 1. One-time laptop setup (about 20 minutes), 1b. Plugins (once per laptop login, about 2 minutes), 2. Repo, 3. Phones, 4. Start the agents (4 terminals), `.claude/agents/mesh-android.md`, `.claude/agents/mesh-core.md`, `.claude/agents/mesh-qa.md` (+19 more)

### Community 11 - "Cmd"
Cohesion: 0.13
Nodes (17): DebugFlag, DROP_RELAY, TAMPER_RELAY, isJpeg(), Attack, Cmd, Debug, Dm (+9 more)

### Community 12 - "Pow"
Cohesion: 0.13
Nodes (9): Pow, PowTimingTest, Bluetooth LE L2CAP (emulators, netsim), Broadcast cost (simulator, real core code, virtual time), Measured numbers, Other, Proof of work (laptop JVM, one core), Still to measure (+1 more)

### Community 13 - "LanTransport.kt"
Cohesion: 0.12
Nodes (4): close(), send(), start(), stop()

### Community 16 - "e2e.sh"
Cohesion: 0.20
Nodes (14): banned_a(), cmd(), delivered(), feed_count(), from_a(), on_c(), q(), received() (+6 more)

### Community 17 - "BleL2capTransport.kt"
Cohesion: 0.12
Nodes (3): decodePsm(), encodeServiceData(), prefixLess()

### Community 19 - "LinkId"
Cohesion: 0.19
Nodes (6): LinkId, TransportListener, Route, Routes, Seen, SeenEntry

### Community 20 - "Sim.kt"
Cohesion: 0.15
Nodes (9): inject(), link(), linkOf(), node(), post(), schedule(), Sent, Task (+1 more)

### Community 22 - ".start"
Cohesion: 0.21
Nodes (4): asUiNode(), UiNode, qrSvg(), UiNode

### Community 23 - "OfflineMesh (Devpost draft)"
Cohesion: 0.18
Nodes (10): Accomplishments we're proud of, Built with, Challenges we ran into, Honest limits, How we built it, Inspiration, OfflineMesh (Devpost draft), What it does (+2 more)

### Community 25 - "OfflineMesh demo"
Cohesion: 0.20
Nodes (9): Before the judges arrive (10 minutes), Driving it from the shell (if a tap fails), Judge questions, OfflineMesh demo, Optional extras (only if time is left), Path 1: real phones, Path 2: emulators (practice only), Path 3: fallback, three desktop nodes (if phone Bluetooth fails) (+1 more)

### Community 27 - "Lead state (OfflineMesh build). Update this file as work moves."
Cohesion: 0.25
Nodes (7): Lead state (OfflineMesh build). Update this file as work moves., Log, Order (section 9), Owner decisions (asked once up front; NO questions during the build), Progress, Rules in force, Where things are (2026-11-14 13:50 CT)

### Community 28 - "OfflineMesh threat model"
Cohesion: 0.25
Nodes (7): Found while building, From the plan (section 12), Known limitations, OfflineMesh threat model, Out of scope, Setting, What an attacker can do, and what stops it

### Community 29 - "OfflineMesh: project instructions for Claude Code"
Cohesion: 0.29
Nodes (6): Commands you can use, If the Ponytail plugin is active, OfflineMesh: project instructions for Claude Code, Phones, Rules, When you are stuck

### Community 30 - "settings.json"
Cohesion: 0.29
Nodes (6): hooks, Stop, permissions, allow, deny, $schema

### Community 35 - "run-3-desktop.sh"
Cohesion: 0.83
Nodes (3): run-3-desktop.sh script, start(), stop()

### Community 36 - "setup-check.sh"
Cohesion: 0.83
Nodes (3): bad(), ok(), setup-check.sh script

## Knowledge Gaps
- **17 isolated node(s):** `TAMPER_RELAY`, `DROP_RELAY`, `FLOOD`, `SPOOF`, `REPLAY` (+12 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 271 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **30 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MeshNode` connect `MeshNode` to `Transport`, `UnitTest.kt`, `GuestServerTest.kt`, `Part 5. The build specification`, `.decodeFrame`, `UiServer.kt`, `RedTeamTest`, `MeshService.kt`, `Cmd`, `Guard`, `LinkId`, `ApiSmokeTest.kt`?**
  _High betweenness centrality (0.269) - this node is a cross-community bridge._
- **Are the 8 inferred relationships involving `MeshNode` (e.g. with `Routes` and `Seen`) actually correct?**
  _`MeshNode` has 8 INFERRED edges - model-reasoned connections that need verification._
- **What connects `TAMPER_RELAY`, `DROP_RELAY`, `FLOOD` to the rest of the system?**
  _17 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `MeshNode` be split into smaller, more focused modules?**
  _Cohesion score 0.09979494190020506 - nodes in this community are weakly interconnected._
- **Why does `Part 5. The build specification` connect `Part 5. The build specification` to `Part 3. Files to create in the repo`, `Sim.kt`, `.decodeFrame`?**
  _High betweenness centrality (0.130) - this node is a cross-community bridge._
- **Should `UnitTest.kt` be split into smaller, more focused modules?**
  _Cohesion score 0.053821800090456805 - nodes in this community are weakly interconnected._
- **Why does `8. Workstreams` connect `.decodeFrame` to `MeshService.kt`, `Part 5. The build specification`?**
  _High betweenness centrality (0.100) - this node is a cross-community bridge._