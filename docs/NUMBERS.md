# Measured numbers

Every number here was measured, unless it says "estimate". Where it ran is stated. Emulator numbers are from Android emulators on the laptop, not real phones.

## Tests

| What | Result |
|---|---|
| `./gradlew :core:test` | 51 tests, all pass, none ignored |
| of which red-team (`core/src/test/kotlin/mesh/redteam/`) | 19, all pass |
| `scripts/e2e.sh` on 3 desktop nodes (TCP, one laptop) | items 1-6 PASS (run twice) |
| `scripts/e2e.sh` on 3 Android emulators (API 36, emulated Bluetooth) | items 1-6 PASS |
| `scripts/e2e.sh` on 5 desktop nodes (`NODES=5`, TCP, one laptop, in a line) | items 1-8 PASS; item 7 (group of all 5) showed `Delivered to 4 of 4` in about 1 s |
| `scripts/e2e.sh` on 4 Android emulators | **TODO: results being collected now, fill in here** |
| `scripts/e2e.sh` on the 3 real phones | **not run yet** |

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
- `e2e.sh` on 4 emulators (TODO above).
- `e2e.sh` on the three real phones, and real Bluetooth range and timing.
