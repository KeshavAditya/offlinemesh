# Measured numbers

Every number here was measured, unless it says "estimate". Where it ran is stated. Emulator numbers are from Android emulators on the laptop, not real phones.

## Tests

| What | Result |
|---|---|
| `./gradlew test` (v2 branch, counted from the test reports) | 146 tests, all pass, none ignored: core 121, uiserver 16, app 4, lan 3, desktop 2 |
| of which red-team (`core/src/test/kotlin/mesh/redteam/`) | 29, all pass (RedTeamTest 26 incl. T1-T7 and T11, BroadcastCostTest 2, PowTimingTest 1); plus 4 guest-server attack tests (T8) in uiserver |
| Browser client tests (`node --test`) | meshcore.js vectors 6/6 against the Kotlin core both ways; browser node against 2 desktop nodes 11/11 |
| `scripts/e2e.sh` on 3 desktop nodes (TCP, one laptop) | items 1-6 PASS (run twice) |
| `scripts/e2e.sh` on 3 Android emulators (API 36, emulated Bluetooth) | items 1-6 PASS |
| `scripts/e2e.sh` on 5 desktop nodes (`NODES=5`, TCP, one laptop, in a line) | items 1-8 PASS; item 7 (group of all 5) showed `Delivered to 4 of 4` in about 1 s |
| `scripts/e2e.sh` on 4 Android emulators | items 1-8 PASS (fresh deploy, no manual mutes; group notification shown on C) |
| v2 on 4 emulators: `e2e.sh` + persistent-link check (10 DMs, `linkUps` must not rise) | items 1-8 PASS (fresh deploy, no manual mutes; item 7 delivered 3 of 3 in 2 s, item 6 C caught up in 3 s). 10 DMs B to D (A is banned on B after item 5), all DELIVERED: `linkUps`/`linkDowns` A 3/0, B 3/0, C 4/1, D 4/1 before and after, so no relinks (C/D's extra up/down happened at startup, before e2e). Network tab shows each link as `ble <mac> rssi 20` (emulator rssi is constant) |
| v2 final, R8 release APK (2.45 MB) on 4 emulators + guest web client | `e2e.sh` items 1-8 PASS (second run; the first failed 7 and 8 only because meshD's emulator Bluetooth stack crashed 10 s after boot, the app relinked once BT was re-enabled). Browser (Playwright, 390x844) at A's guest port: 1 web link on A next to its 2 BLE links (none refused); DM browser to C 3 hops via A and B DELIVERED, C's reply received. `/app.apk` SHA-256 = shown hash = installed base.apk = build output |
| Real phones, one run (OPPO + A52s over Bluetooth, laptop desktop node over LAN with a static peer; no Fold 7) | items 1, 2, 3, 4, 6, 7 PASS; item 5 FAIL: the OPPO went silent mid-test (B logged LINK_CLOSED after 45 s silent, adb to the OPPO hung), so Android battery saving or ColorOS stopped the app. B's flood and ban logic was correct. One BLE DM took 560 ms. Source: orchestrator log, 21:04. Not re-run since the battery prompt was added |

## Broadcast cost (simulator, real core code, virtual time)

One public post from one node. "Transmissions" counts every time any node put that post on any link.
The plan's bound is 2 x links. Random connected graphs, at most 5 links per node.

| Nodes | Links | Transmissions | Bound | Received |
|---|---|---|---|---|
| 3 (line) | 2 | 2 | 4 | 2 / 2 |
| 10 | 15 | 15 to 16 | 30 | 9 / 9 |
| 25 (seed 7) | 40 | 40 to 42 | 80 | 24 / 24 |
| 25 (plan test 10.2 #9, seed 42) | 40 | 41 to 42 | 80 | 24 / 24 |

About one transmission per link, whatever the node count. The count moves by 1 or 2 between runs because forward jitter is random.
Source: `BroadcastCostTest` and `SimTest.t9_randomGraphBroadcastCost` (lines starting `BCAST` and `10.2 #9` in the test output).

**Large meshes:** at 50 nodes / 90 links one post costs 92 transmissions (bound 180); with sender-side pacing (25 per 10 s per link) there are no security events and all 49 other nodes get the post.

## Proof of work (laptop JVM, one core)

| Message | Bits | Average time | Runs |
|---|---|---|---|
| POST (any size up to 1,000 B) | 15 | 6 to 9 ms | 40 |
| Largest DM (40,000 B plaintext, also a photo DM) | 22 | 0.6 to 1.2 s | 4 (expected 0.9 s from the hash rate) |

- Hash rate: 3.9 to 4.7 million SHA-256 tries per second on one core.
- So an attacker's laptop mines about 110 to 165 posts per second per core; about 1,000 per second on 8 cores (estimate from the one-core figure).
- Phones: **not measured yet.** Expect several times slower than the laptop (estimate): a post is still well under a second, the largest DM a few seconds.
- Source: `PowTimingTest` (lines starting `POW`).

## Cost of one group message

A group message is sent as one ordinary DM to each other member, so it costs **(members − 1) DMs**, each with its own proof of work and its own ACK.

- A short group text (under 2,048 bytes of body) is a DM at 16 bits: 14 + 0 for size + 2 for TTL 8. That is twice the work of a 15-bit post, so about 12 to 18 ms per DM on the laptop (estimate from the 6 to 9 ms post time and the hash rate above).
- So on the laptop: 3 members about 30 ms, 5 members about 60 ms, the 16-member cap (15 DMs) about 0.2 to 0.3 s of mining in total (estimate).
- On the network, each of those DMs follows its own route, so the transmissions are the sum of the hop counts to each member.
- Phones: not measured; expect several times slower (estimate).

## Bluetooth LE L2CAP (emulators, netsim)

| What | Result |
|---|---|
| First spike: advertise, scan, connect, exchange "hello" | about 1 s, first attempt |
| One 59,990-byte frame (near the 60,000 B max) | 4 to 6 s, CRC matches both ways |
| Link closed, then redial | under 1 s |
| Bluetooth toggled off and on, new channel, relink | about 4 s |
| 3 emulators | full mesh, 2 authenticated links each |

## Other

| What | Result | Where |
|---|---|---|
| Photo DM | 822 KB photo downscaled in the browser to 36 KB; bytes identical after A -> B -> C; status Delivered, 2 hops | desktop nodes |
| Missed post after reconnect (acceptance item 6) | must arrive within 20 s; e2e passes on desktop and emulators | desktop, emulators |

## Still to measure

- PoW time on each real phone.
- A full `e2e.sh` pass on the three real phones (after the battery fix), and real Bluetooth range and timing.

## Forwarding benchmark in plain words

The table below is written by `./gradlew :core:bench`. Every figure in this summary is copied from it. The 30-in-a-room rows are the ones to quote.

- **DMs are routed, not flooded.** A delivered DM costs 5.17 DM transmissions with our routing (CURRENT) against 97.80 when every node forwards everything (NAIVE). Its ACK costs 3.44 more.
- **Posts must reach everyone.** CURRENT sends 66.2 full copies per post against 88.5 for NAIVE, and delivers more (0.993 against 0.983).
- **TREE is a trade-off, not a free win.** It sends 36.2 copies per post (about half of CURRENT) and 307 bytes per delivered post against 478 (36% fewer). But post delivery drops from 0.993 to 0.898 and the 95th-percentile delay rises from 332 to 2195 ms. The reason: tree paths are longer than shortest paths, so the 6-hop TTL runs out before far nodes. That is why nodes ship with `FLOOD` (CURRENT) and TREE stays off by default.
- **Three phones in a line save nothing** with TREE: 2.0 sends per post in every mode except NAIVE (2.2). The saving only shows in larger meshes.
- **Relays that drop DMs (U10).** With 3 of 30 nodes dropping, DM delivery is 0.993 with and without failover, because sync also hands stored DMs to other neighbours. Failover cuts the slowest DMs: 99th-percentile delay 14208 ms without U10, 8342 ms with it.

<!-- BEGIN U11 BENCH (generated by ./gradlew :core:bench) -->
## Forwarding benchmark (U11, simulator, real core code, virtual time)

Generated by `./gradlew :core:bench` (core/src/test/kotlin/mesh/bench/ForwardingBench.kt); do not edit by hand. Wall time 99 s.

Setup: topology seed 11; links have 5 ms latency. Random layouts link each node to its 5 nearest, shortest first, capped at 5 links per node (MeshNode refuses more); the wide area is 100 m x 100 m with a 25 m range, the room 10 m x 10 m.
Workload: PRESENCE settles for 40 s, then every node sends 2 posts and 10 DMs to random other nodes at random times over 120 s, then 120 s more to deliver. Full workload, not shrunk.
NAIVE: every new envelope goes to every link except the one it came on (a test-only switch, `MeshNode.naiveFlood`), without U10 failover. CURRENT: guide 5.7 as built, with U10 DM failover (shipped `BROADCAST_MODE = FLOOD`). TREE: CURRENT plus U9 tree broadcast for POST and PRESENCE (`BROADCAST_MODE = TREE` on every node): full copies on EAGER links, 8-byte IHAVE notices on LAZY ones, GRAFT after 1500 ms. ACKs walk the DM's path back in all modes. TTL 6 counts hops along the path taken, and tree paths are longer than shortest paths, so TREE can lose far nodes that FLOOD reaches. Key pairs and forwarding jitter are not seeded, so reruns differ slightly.

Columns: delivery ratios (posts: copies received / posts x (nodes - 1); DMs: received / sent); full POST envelope frames sent per post (relays and sync answers); POST frame bytes on the air per delivered post copy, plus the IHAVE and GRAFT bytes for post ids (frame header shared pro rata with PRESENCE ids) and every PRUNE; DM envelope frames and ACK frames per delivered DM; median and 95th-percentile delivery time over all delivered posts and DMs, virtual ms.

| Topology | Mode | Post delivery | DM delivery | Sends per post | Bytes per delivered post | DM tx per delivered DM | ACK tx per delivered DM | Median ms | p95 ms |
|---|---|---|---|---|---|---|---|---|---|
| line of 3 (3 nodes, 2 links) | NAIVE | 1.000 | 1.000 | 2.2 | 225 | 1.57 | 1.23 | 5 | 79 |
| line of 3 (3 nodes, 2 links) | CURRENT | 1.000 | 1.000 | 2.0 | 207 | 1.23 | 1.23 | 5 | 72 |
| line of 3 (3 nodes, 2 links) | TREE | 1.000 | 1.000 | 2.0 | 208 | 1.23 | 1.23 | 5 | 72 |
| 5x5 grid (25 nodes, 40 links) | NAIVE | 0.967 | 0.952 | 51.8 | 464 | 53.66 | 3.28 | 136 | 297 |
| 5x5 grid (25 nodes, 40 links) | CURRENT | 0.967 | 0.952 | 39.1 | 350 | 3.38 | 3.21 | 140 | 307 |
| 5x5 grid (25 nodes, 40 links) | TREE | 0.930 | 0.952 | 27.0 | 262 | 3.37 | 3.21 | 232 | 2187 |
| 30 in a room (30 nodes, 68 links) | NAIVE | 0.983 | 0.993 | 88.5 | 645 | 97.80 | 4.21 | 226 | 3813 |
| 30 in a room (30 nodes, 68 links) | CURRENT | 0.993 | 0.993 | 66.2 | 478 | 5.17 | 3.44 | 156 | 332 |
| 30 in a room (30 nodes, 68 links) | TREE | 0.898 | 0.993 | 36.2 | 307 | 5.36 | 3.44 | 242 | 2195 |
| 50 over a wide area (50 nodes, 109 links) | NAIVE | 0.774 | 0.732 | 102.3 | 560 | 134.31 | 4.31 | 670 | 22819 |
| 50 over a wide area (50 nodes, 109 links) | CURRENT | 0.831 | 0.786 | 86.5 | 441 | 4.57 | 3.69 | 196 | 363 |
| 50 over a wide area (50 nodes, 109 links) | TREE | 0.750 | 0.818 | 46.6 | 279 | 4.86 | 3.79 | 293 | 3171 |

### Failure run: 30 in a room, 10% of nodes on DROP_RELAY

The same room, 3 of 30 nodes switched to DROP_RELAY after PRESENCE settled; DMs only (10 per node). Periodic SYNC (every 15 s) also hands stored DMs to non-dropping neighbours, so compare the delay columns with the room rows above. U10: an own DM with no ACK in 3 s + 1 s per route hop is resent on a backup route through a different first link (else flooded once), at most twice; "without U10" switches that off (`MeshNode.failover`).

| Mode | DM delivery | DM tx per delivered DM | Median ms | p95 ms | p99 ms |
|---|---|---|---|---|---|
| NAIVE | 0.993 | 90.68 | 178 | 732 | 1480 |
| CURRENT without U10 | 0.993 | 5.45 | 166 | 424 | 14208 |
| CURRENT with U10 | 0.993 | 5.25 | 170 | 418 | 8342 |
<!-- END U11 BENCH -->
