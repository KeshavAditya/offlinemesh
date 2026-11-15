# OfflineMesh update plan v2

> **Orchestrator notes (owner decision 2026-11-14 16:20 CT; these win over the plan text below where they differ):**
> - **Branch:** everything in this file is built on the `v2` branch. It is tested in the simulator, on desktop nodes and on the 4 emulators. It is **not merged to `main` and not installed on real phones** until the owner has run acceptance checks 1 to 6 on the real phones (section 0). No phones are connected right now.
> - **Frame codes:** `0x14` is already taken by BAN_PROOF (ban-proof gossip, guide stretch 4, built). U9 therefore uses **`0x15` IHAVE, `0x16` GRAFT, `0x17` PRUNE**.
> - **U9b (2026-11-14):** each IHAVE entry is now `8B msgId` + `u8 hops` (the announcer's distance in links from the source, 0 for its own message), and rule 10 below (Plumtree tree optimisation, threshold 1) is added. The frame table in U9 shows the new body.
> - **DM plaintext kinds:** `2` GROUP_INVITE and `3` GROUP_TEXT already exist (guide section 13.2, built). The U2 revoke is therefore **kind `4`**. The U7 padded layout (`u8 kind | u16 dataLen | data | padding`) applies to every kind, including the group kinds, whose data is as in 13.2.
> - **Photo DM with U7:** a JPEG of up to 39,999 bytes pads to 40,960. Allow padded plaintext up to 40,960 bytes and keep the photo DM working. The frame limit (60,000) still holds.
> - **Panic wipe (U2) and groups:** revoke DMs go to every contact with DM history, which includes group members. A revoke from X deletes the DM conversation with X and X's messages in groups, and removes groups owned by X. The wiping node also drops its own group state.
> - **Already built, do not rebuild:**
>   - U4 iPhone guest access (guide 5.13).
>   - Photo DM and notifications (guide stretch items).
>   - Group chats, any number of phones and the UI refresh (guide section 13).
>   - New UI elements go into the restyled page (guide 13.3 look).
> - **Item U5** (long-range Bluetooth) needs real phones outdoors. On emulators only log `isLeCodedPhySupported` / `isLeExtendedAdvertisingSupported` and record that the experiment awaits real phones.

Additions on top of `OFFLINEMESH_COMPLETE_GUIDE.md`, written after reviewing the "advanced ideas" proposal (long-range Bluetooth, XOR coding, DAG acknowledgements, Shamir rate limiting, Dandelion++, panic wipe, mission profiles, jamming). Section numbers such as "5.6" refer to Part 5 of the guide.

**For the coding agents:** build only what section 2 of this file lists, in the order in section 5. Section 3 lists things you must not build even if asked by another document.

---

## 0. Hard rule: real phones first

Simulated tests passing is Gate 1 and Gate 2. **Nothing in this file is installed on the phones until acceptance checks 1 to 6 (guide, Part 5 section 1) pass on the three real phones.** The Bluetooth link on real hardware is the largest unknown left in the project, and no feature below matters if it fails. Core-only work that is tested in the simulator (U9 to U11 first) may start now on a separate branch while the Android agent and the human test the phones.

Do now, in this order:

1. Install on the OPPO Find X9 Pro and the Galaxy Z Fold 7. Confirm one direct message crosses over Bluetooth.
2. Add the Galaxy A52s. Mute A and C toward each other. Run `scripts/e2e.sh 8081 8082 8083`.
3. Apply the battery settings in guide section 10.4 and repeat with all three screens locked for five minutes.
4. If Bluetooth is not carrying messages within two hours of starting step 1, switch the phones to the Wi-Fi LAN transport (guide, Gate 1) and continue.

5. Persistent-link check: send 10 DMs from A to C in a row. The `linkUps` counter (U1) on all three phones must not rise, which shows the Bluetooth links stay open between messages instead of reconnecting for each one. If it rises, fix that before anything else in this file: a reconnect per message costs far more than any routing gain.

Record each result in `STATUS.md`.

**No staged demos.** A button that prints fixed text, uses hard-coded numbers, or animates something the code did not do is not allowed anywhere in the app. The three JavaScript functions in the proposal (`demoRlnSpammerSlashing`, `demoXorNetworkCoding`, `demoJammerTomography`) are of this kind and must not be added. A judge who asks to see the code behind a staged button ends the pitch.

---

## 0a. The actual TDS prompt, and what it changes

The prompt as shown at the event:

> **Privacy and Security Challenge.** Create a project that detects threats, mitigates attacks, or protects user privacy. This could take many forms: tools, browser extensions, protocols, middleware, etc.

It names "protocols" as a valid form and says nothing about range, hop counts or being offline. Three consequences:

1. **For TDS the product is a secure messaging protocol.** The mesh is the setting that makes security hard (every relay is a stranger's phone). Lead the TDS pitch with security, not with distance.
2. **Range and efficiency ideas earn nothing on this prompt.** Long-range Bluetooth, XOR coding, DAG acknowledgements and neighbour selection are performance work. U5 drops to last.
3. **The existing build already covers all three verbs with working code.** The job now is to show that clearly and add two small real security features (U7, U8), not to add simulated cryptography.

| Prompt verb | Already built (guide section) | Added by this file |
|---|---|---|
| Protects user privacy | End-to-end encrypted DMs that relays cannot read (5.4); fingerprint verification of contacts | U7 length padding; U2 panic wipe |
| Detects threats | Security log: bad signature, bad proof of work, replay, link flood, rate-limit equivocation (5.6) | U8 impersonation warning; U10 detects relays that drop DMs; U1 counts by kind |
| Mitigates attacks | Proof of work, three posts per epoch, link scoring and closing, bans backed by signed proof (5.6 to 5.8) | U10 reroutes around dropping relays; U9 bounds what each post costs the network |

A judge has also asked whether the mesh "just spams messages to everyone". Section 2b answers that with measured numbers and two routing improvements. Efficiency counts for TDS too: the less each message costs the network, the less damage a spammer can do within its rate limit.

---

## 1. Verdict on each proposed idea

| Idea | Verdict | Reason in one line |
|---|---|---|
| Mission profile switcher | **Build, reduced** (U3) | Real if it only changes local timers; must never change anything other nodes validate |
| Panic wipe | **Build, reduced** (U2) | Local wipe is real and cheap; the remote "shred" can only be a signed request, not a guarantee |
| Long-range Bluetooth (Coded PHY) | **Experiment, time-boxed** (U5) | Real radio mode, but phone support is patchy and 800 m is a dev-board figure |
| Air-gap transfer by QR | **Optional, last** (U6) | The honest answer to "what if the radio is jammed"; costs 2 to 3 hours |
| Live measured statistics | **Build** (U1, new) | Not in the proposal; real numbers beat every claim in it |
| DM length padding | **Build** (U7, new) | Stops relays learning message length; small and fully real |
| Impersonation warning | **Build** (U8, new) | A real "detects threats" feature for an open network |
| Shamir rate limiting with "secret key extraction" | **Do not build** | Unverifiable without zero-knowledge proofs; the proposed code derives the "secret" from the public ID |
| XOR network coding at the relay | **Do not build** | Needs a broadcast radio; our links are point-to-point, so it saves nothing |
| "Mysticeti" DAG acknowledgements | **Do not build** | Changes the signed message format for no visible gain; the name does not fit |
| Longest-reach neighbour selection | **Do not build** | A DM already goes direct when the phones can reach each other; this only picks weaker links. U9 and U10 are the forwarding improvements that do apply |
| Tree broadcast for posts (Plumtree) | **Build** (U9, new) | The proposal named it; it is the efficiency gain that works on one-to-one links |
| DM failover and black-hole detection | **Build** (U10, new) | Real "detect and mitigate" for relays that drop messages |
| Forwarding benchmark | **Build first** (U11, new) | Turns "does it spam everyone?" into a table of measured numbers |
| Batching several messages per Bluetooth connection | **Already built** (guide 5.5, Agent B) | Links stay open; queued frames go back to back on one socket. Verify with the persistent-link check in section 0 |
| Prefer neighbours at −65 to −80 dBm | **Do not build** | Same idea as longest-reach selection; a phone already sends direct to any phone it can reach |
| Reverse-path route cache, unicast after the first message | **Already built** (guide 5.7) | Routes come from PRESENCE beacons, so even the first DM is unicast; U10 adds a backup route |
| Dandelion++ origin hiding | **Do not build** | Does not hide the sender from a radio sniffer; our own advertisements identify every phone |
| Jamming detection and rerouting map | **Do not build** | Android exposes no noise measurement; a jammed link looks the same as a phone walking away |
| Duress PIN, decoy history | **Do not build** | The app has no lock screen to attach them to |
| Automatic shift to 5 GHz | **Do not build** | The LAN transport over a 5 GHz hotspot already exists as a manual fallback |

---

## 2. Updates to build

All additions are additive. No change to the envelope or frame formats in guide sections 5.2 and 5.3.

### Interface additions (extends guide section 6)

```kotlin
enum class Profile { STANDARD, CROWD, TUNNEL, SPARSE }

// added to MeshNode
fun stats(): Stats
fun setProfile(p: Profile)
fun panicWipe()                                   // U2
fun exportEnvelope(msgIdHex: String): ByteArray?  // U6
fun importEnvelope(bytes: ByteArray): Boolean     // U6
```

UI protocol additions:

```json
// server to browser: snapshot gains
"profile":"STANDARD",
"stats":{"originated":0,"relayed":0,"duplicatesDropped":0,"forwardsSkipped":0,
         "bytesOut":0,"bytesIn":0,"ackRttMsLast":0,"ackRttMsMedian":0,
         "powMsLast":0,"syncRounds":0,"syncRecovered":0,
         "drops":{"BAD_SIGNATURE":0,"BAD_POW":0,"MALFORMED":0,"STALE":0,"LINK_FLOOD":0}}
{"t":"stats","stats":{...}}          // pushed every 2 s while a client is connected

// browser to server
{"t":"profile","name":"TUNNEL"}
{"t":"panic","confirm":"WIPE"}
```

Also `GET /api/stats`.

### U1. Live measured statistics (about 1 hour; core + UI)

Counters kept by `MeshNode`, reset on restart:

| Counter | Counts |
|---|---|
| `originated` | envelopes this node created |
| `relayed` | envelopes forwarded for others |
| `duplicatesDropped` | arrivals rejected at ingress step 2 (already seen) |
| `forwardsSkipped` | sends avoided because the neighbour was known to have the message |
| `bytesOut`, `bytesIn` | frame bytes per node |
| `ackRttMsLast`, `ackRttMsMedian` | time from sending a DM to its verified ACK |
| `powMsLast` | time spent mining the last proof of work |
| `syncRounds`, `syncRecovered` | sync exchanges, and envelopes obtained through sync |
| `linkUps`, `linkDowns` | authenticated links opened and closed since start |
| `drops` | ingress rejections by kind |

UI: a plain two-column table "Statistics" at the bottom of the Network section. The Bluetooth transport adds the signal strength seen at discovery to the link description (`ble 4A:.. rssi -61`); show it in the links table. No routing decision uses it.

Done when the numbers move during the demo script and `scripts/e2e.sh` prints `/api/stats` for all three nodes at the end. Agent D copies real figures into `docs/NUMBERS.md`.

### U2. Panic wipe (about 2 hours; core + UI)

**Local wipe**

1. Network section gets a "Panic wipe" button. It asks the user to type `WIPE`.
2. For every contact with DM history, send one DM whose plaintext is `kind = 2` (revoke) with empty data. Wait at most 3 seconds for the sends to be handed to a link.
3. Stop the node. Delete `identity.bin`, `messages.log` and the contact table. Clear all in-memory state.
4. Generate a new identity and start. The UI reloads an empty snapshot with a new NodeId.

**On receiving a `kind = 2` DM from X**

- Delete the stored conversation with X from memory and rewrite `messages.log` without it.
- Mark contact X as revoked. Drop later DMs from that key. Show "revoked" in the contacts table.
- Log `PEER_REVOKED` in the security list.

The revoke travels inside an ordinary encrypted, signed DM, so only the holder of X's key can trigger it and relays cannot tell it apart from a normal message.

**Limits to state plainly (add to `docs/THREATS.md`)**

- It is a request. A modified or offline recipient keeps its copy.
- Deleted files on flash storage may be recoverable with forensic tools.
- Relay copies are ciphertext and expire within the hour; they are not recalled.
- It protects only if the button is pressed before the phone is taken.

Simulator test: A and C exchange DMs; A calls `panicWipe()`. Then C holds no conversation with old-A, C logged `PEER_REVOKED`, A's NodeId changed, and A's data directory holds no old envelopes.

### U3. Mission profiles (about 1.5 hours; core + UI)

A profile changes **local timers and limits only**. It must never change a value another node checks (TTL caps, proof-of-work bits, epoch length, slots per epoch, maximum age, frame limits). Nodes on different profiles must interoperate.

| Setting | STANDARD | CROWD | TUNNEL | SPARSE |
|---|---|---|---|---|
| Intended for | default | dense venue, protest | cave, corridor, single chain of people | few people far apart, search and rescue |
| Presence interval | 30 s | 60 s | 15 s | 15 s |
| Sync interval | 15 s | 30 s | 5 s | 10 s, plus at every link-up |
| Forward jitter | 30 to 100 ms | 50 to 200 ms | 0 to 20 ms | 30 to 100 ms |
| Max links | 5 | 4 | 5 | 5 |
| Relay store cap | 256 | 256 | 512 | 512 |
| Long-range advertising (U5) | off | off | on if supported | on if supported |

UI: a `<select>` in the Network section, and under it a small table showing the active values, so anyone can see what the switch does.

Simulator test: line A–B–C with A on TUNNEL, B on CROWD, C on SPARSE; acceptance checks 1, 3 and 6 still hold.

Say in the pitch: "profiles trade airtime against delivery speed". Do not claim they add security features.

### U4. iPhone guest access

Unchanged from guide section 5.13. Build it after U1; it lets a judge join from their own phone with no install.

### U5. Long-range Bluetooth experiment (hard limit 2 hours; Android agent + human)

Coded PHY is a real Bluetooth 5 mode that trades speed (125 kbit/s) for range. Phone support is inconsistent: some phones report support but cannot scan on it or cannot open connections on it.

1. On each phone log `adapter.isLeCodedPhySupported` and `adapter.isLeExtendedAdvertisingSupported` to `STATUS.md`.
2. Keep the existing legacy advertising unchanged. When the profile enables it, start a **second** advertising set: extended, connectable, primary and secondary PHY coded, same service UUID and service data.
3. Scanner: `setLegacy(false)` and `setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)`. Record `ScanResult.primaryPhy`.
4. Try the L2CAP connect to a peer discovered on the coded PHY. If that fails, connect on the normal PHY as today and then request coded through a short-lived GATT connection (`setPreferredPhy` with the coded masks and `PHY_OPTION_S8`); log whether `onPhyUpdate` reports coded. This second route is unverified; try it once.
5. Show the active PHY in the links table.

**Exit:** if no link is running on the coded PHY after 2 hours, remove the toggle, write what happened in `STATUS.md`, and move on. A documented negative result is still worth one sentence in the pitch.

**If it works, measure it.** Two phones outdoors with line of sight, one DM every 5 seconds, walk apart until three in a row fail. Record the distance for normal and for coded. Quote only these measured numbers. Expect far less than 800 m.

### U6. QR air-gap hop (optional, about 3 hours; only if everything above is done by 2:00 AM)

Moves one message between two phones with every radio off, as the honest answer to "what if Bluetooth is jammed".

- Native Android screen, not the web page: two buttons, "Show message as QR" and "Scan QR".
- Library: ZXing (`com.journeyapps:zxing-android-embedded`), which works offline. It needs the camera permission.
- Show: `exportEnvelope(msgId)` returns the raw envelope bytes; encode them as one QR code in byte mode. Limit to envelopes of 500 bytes or less (short text only); disable the button otherwise.
- Scan: decoded bytes go to `importEnvelope`, which runs the **full ingress pipeline** (guide 5.6) on a pseudo-link named `qr` with no link score. An accepted envelope is then stored, delivered or forwarded like any other.
- Demo: airplane mode with Bluetooth off on A and B; A shows a post as QR; B scans; B turns Bluetooth on; the post reaches C.

Simulator test: export from one node, import into another with no link between them; signature and proof of work still verify; a QR with one flipped byte is rejected.

### U7. DM length padding (about 45 minutes; core)

Today a relay can read the exact length of every encrypted DM. Padding hides it.

New DM plaintext layout (replaces the one in guide 5.3; all phones must run the same build):

```
u8 kind | u16 dataLen | data | zero bytes up to the bucket size
```

- Total plaintext length is the smallest bucket that fits `3 + dataLen`: 64, 128, 256, 512, 1024, 2048, 4096, then multiples of 4096.
- `kind`: 0 text, 1 JPEG, 2 revoke (U2, `dataLen = 0`).
- Receiver: reject if `dataLen` exceeds the remaining bytes; ignore the padding.
- Encryption, IV, tag and associated data are unchanged (guide 5.4).

A revoke now has the same size as any short text, so a relay cannot single it out.

Tests: a 1-character and a 50-character DM produce envelope bodies of equal length; round trip for all three kinds; a `dataLen` larger than the plaintext is rejected as `MALFORMED`.

Say: "relays see sender, recipient, timing and a size bucket, never content or exact length."

### U8. Impersonation warning (about 45 minutes; core + UI)

Anyone can join and pick any display name, so a newcomer can copy the name of someone you trust. Keys cannot be copied; names can. Detect it.

- On a PRESENCE from key K with name N: if a **different** contact that the user marked verified has the same name (compare trimmed, case-insensitive), mark K as suspect and log `NAME_IMPERSONATION` with K as `src` and the verified contact's ID in `detail`. Log at most once per key per 10 minutes.
- Snapshot: each contact gains `"suspect": false`. Security kinds gain `NAME_IMPERSONATION` and `PEER_REVOKED`.
- UI: every unverified contact's name is shown with the first 4 hex digits of its ID (`Sam · 3fa9`). A suspect contact shows the text "same name as a verified contact" beside it in the contacts table and at the top of its conversation.
- No automatic ban: sharing a name is not proof of malice.

Simulator test: B verifies A; C sets its name to A's name; B logs `NAME_IMPERSONATION` once, C is suspect on B, A is not.

---

## 2b. Smart forwarding: "does it just spam everyone?"

### What the build already does (guide 5.7)

- **Direct messages are not broadcast.** Every phone learns, from signed PRESENCE beacons, which neighbour leads to each person by the fewest hops. A DM goes down that one path, one transmission per hop, and its ACK walks the same path back. Only when no route is known does a DM fall back to a flood.
- **Public posts must reach everyone**, but each phone forwards a post once per link, never back to a neighbour that already has it, and never beyond 6 hops.
- **Hop skipping is automatic.** If A can reach C directly, the route is direct; a relay is used only when the phones cannot reach each other. On the demo desk the path goes through B only because A and C are muted toward each other.

The remaining waste: every link still carries the full post, so in a dense crowd most copies arrive at phones that already have it. U9 removes that. U10 handles relays that drop messages. U11 measures all of it.

### U11. Forwarding benchmark (about 1 hour; core) — do this first

A Gradle task `./gradlew :core:bench` that runs the virtual-time simulator and writes a table to `docs/NUMBERS.md`.

- **Modes:** `NAIVE` (forward every new envelope to every link except the one it came on; a test-only mode, never shipped), `CURRENT` (guide 5.7), and `TREE` once U9 exists.
- **Topologies, fixed random seed:** line of 3; 5 × 5 grid; 30 nodes placed at random in a room, each linked to its 5 nearest; 50 nodes placed at random over a wider area.
- **Workload:** let PRESENCE settle, then every node sends 2 posts and 10 DMs to random nodes.
- **Columns:** delivery ratio for posts and DMs; full-envelope sends per post; bytes on the air per delivered post (including IHAVE, GRAFT and PRUNE); transmissions per delivered DM; median and 95th-percentile delivery time in virtual milliseconds.
- **Failure run:** the same 30-node room with 10% of nodes set to `DROP_RELAY`: DM delivery ratio with and without U10.

Quote only numbers from this table. Commit it so the figures can be traced to code.

### U9. Tree broadcast for posts (about 3.5 hours; core)

Plumtree ("Epidemic Broadcast Trees", Leitão, Pereira and Rodrigues, SRDS 2007). It was designed for networks built from one-to-one connections, which is what L2CAP links are. Full posts travel along a spanning tree that forms by itself. Every other link carries only an 8-byte "I have it" notice, used to repair the tree when a link drops.

New frames (extends guide 5.3; all phones must run the same build):

| Code | Frame | Body |
|---|---|---|
| `0x15` | IHAVE | `u16 count` then `count` × (8-byte msgId, `u8 hops`) (64 at most); `hops` = the announcer's distance in links from the source (0 = its own message, capped at 255) |
| `0x16` | GRAFT | `u16 count` then `count` × 8-byte msgId (64 at most) |
| `0x17` | PRUNE | empty |

New constants: `BROADCAST_MODE` = `TREE` or `FLOOD` (default `FLOOD` until the real-phone checks pass in `TREE`), `IHAVE_FLUSH_MS` = 300, `GRAFT_TIMEOUT_MS` = 1,500.

Rules, applied to POST and PRESENCE envelopes only (DMs and ACKs are unchanged):

1. Each authenticated link is `EAGER` or `LAZY` per node. A new link starts `EAGER`.
2. **First copy of an envelope** (from link L, or created locally): deliver it; after the usual jitter, send the full envelope to every `EAGER` link except L and links known to have it; queue its msgId for every `LAZY` link except those. Flush queued IDs as one IHAVE per link every `IHAVE_FLUSH_MS`.
3. **Duplicate full copy from link L:** mark L `LAZY` and send PRUNE on L.
4. **PRUNE received on L:** mark L `LAZY`.
5. **IHAVE received:** for each ID not seen, remember the announcing link and start a `GRAFT_TIMEOUT_MS` timer. When it fires and the ID is still unseen, send GRAFT on that link (if it is down, try the next link that announced the same ID) and mark that link `EAGER`.
6. **GRAFT received on L:** mark L `EAGER`; send every requested envelope still held. Ignore IDs not held. These envelopes are exempt from the link token bucket, like SYNC_WANT.
7. IHAVE, GRAFT and PRUNE count against the link token bucket. A malformed one is `MALFORMED` against the link, as for any frame.
8. In `FLOOD` mode a node sends none of these frames. If it receives them: answer IHAVE like a SYNC_OFFER, answer GRAFT by sending the envelopes, ignore PRUNE. Nodes in different modes therefore still interoperate.
9. Sync every 15 seconds (guide 5.8) is unchanged and remains the last safety net.
10. **Tree optimisation (Plumtree, SRDS 2007, POST only):** a node holding POST m at distance h, received over link E, that gets an IHAVE for m on another link L with hops h' where h' + 1 < h (threshold 1, picked by measurement over 2), sends GRAFT(m) on L (L becomes `EAGER`; its copy has more ttl left and is relayed to links that lacked it) and PRUNE on E (E becomes `LAZY`). The tree moves toward shortest paths. Not applied to PRESENCE: every node beacons every 30 s and would re-root the one shared tree toward each node in turn. When rule 5 grafts, it picks the live announcer with the smallest hops.

Why this is safe against attack: a link can only prune or graft itself, because links are authenticated (rule 10 prunes E on L's unsigned hops claim, but a liar only becomes the parent; if it withholds, E still announces and rule 5 grafts E back); a neighbour that withholds posts is routed around by rule 5; a fake IHAVE gets a GRAFT that is never answered, and costs the faker its own token bucket.

Additions to U1: counters `fullSends`, `ihaveIds`, `grafts`, `prunes`; the links table gains a column "Tree" (`eager` or `lazy`).

Simulator tests: on the 5 × 5 grid, after 20 posts, each later post costs at most `nodes − 1 + 4` full sends (a settled tree plus a few repairs); cut one tree link in the middle of a post and every node still receives it within `GRAFT_TIMEOUT_MS` + 1 s; guide simulator tests 1 to 11 all still pass with `BROADCAST_MODE = TREE`.

With three phones in a line the tree is the line, so nothing is saved on the demo desk. The saving shows in the benchmark; say so.

### U10. DM failover and black-hole detection (about 1.5 hours; core + UI)

A relay can drop every DM it is given (`DROP_RELAY`), and because the `hops` field is outside the signature (guide section 12), a malicious relay can also understate it to attract routes. The sender sees the result: no ACK.

1. Route learning keeps up to two routes per destination: **best** (fewest hops, freshest) and **backup** (best route through a **different** first link).
2. When sending its own DM, the node starts a timer of `3,000 + 1,000 × hops` ms.
3. If no valid ACK arrives in time and a fresh backup exists: send the **same envelope bytes** (same msgId) on the backup link, stop using the failed first link for that destination for `ROUTE_TTL`, and log `ROUTE_FAILED` with `link` = the failed first hop and `src` = the destination. If there is no backup, flood once (today's fallback).
4. At most two resends per DM. The status line shows "rerouted via D" when the backup was used.
5. Nothing is banned. A dropped link and a malicious relay look the same, so the response is only a routing preference, and the log entry makes it visible.

Security kinds gain `ROUTE_FAILED`. Relays need no change: a resent copy is a duplicate only on nodes that already saw it.

Limitation to state: only the first hop is guaranteed to differ, so a black hole that sits on both paths further on is not avoided.

Simulator test: diamond A–B–D and A–C–D; turn on `DROP_RELAY` on whichever node A's route to D uses; A DMs D; D receives it through the other node, A shows `DELIVERED` and "rerouted", and A logged `ROUTE_FAILED` once.

**Demo:** three phones in a line cannot show this, because there is no second path. Show it on four desktop nodes on the laptop (the same core code over the LAN transport) arranged as the diamond by muting, and say plainly that it is the desktop build.

### Ideas reviewed that are already covered (do not rebuild)

- **Batching messages on one connection.** The proposal assumes a socket is opened per message. This build opens one authenticated L2CAP link per neighbour and keeps it open; each link has one writer thread that sends queued frames (envelopes, ACKs, sync offers) back to back as length-prefixed frames. The connection setup is paid once per neighbour, not per message. Only check that the Android implementation really does this (section 0, step 5).
- **Reverse-path route cache.** Guide 5.7 already records, for every source, the link and hop count of the best copy seen, and sends DMs unicast on that link. Because every phone floods a signed PRESENCE beacon, routes exist before the first DM, so no discovery flood is needed. Replies and ACKs keep routes fresh.
- **Preferring weaker, farther neighbours.** Not used. On one-to-one links a broadcast is sent to each linked neighbour anyway, and a DM to a phone in direct range already goes direct, so there is no middle hop to skip. Preferring weak links only makes links drop more often.

### Answer for the judge

> "No. Direct messages are routed: each phone learns from signed beacons which neighbour leads to each person, so a DM takes one path, one transmission per hop, and the receipt comes back the same way. Public posts have to reach everyone, so they travel down a self-repairing broadcast tree: each phone receives the full post once, and the other links carry only an 8-byte notice used for repair. In our simulator with 30 phones in a room, that costs [figure from NUMBERS.md] transmissions per post against [figure] for plain flooding. If a relay starts dropping messages, the sender notices the missing receipt and reroutes."

Fill both figures in from `docs/NUMBERS.md`; do not estimate them.

---

## 3. Do not build, and why

**Shamir rate limiting with key extraction.** The real scheme (Rate-Limiting Nullifiers, used by Waku) attaches a zero-knowledge proof to every message showing the share was computed correctly from a registered secret. Without that proof a relay cannot check the share, so a spammer attaches random numbers and nothing is ever extracted. The proposed code also derives the "secret" as a hash of the public node ID, so anyone can compute everyone's secret, and the recovered value is not the signing key, so it cannot sign a revocation. Building real zero-knowledge circuits is not possible in the time left. What is already built does the same job for public identities: exceeding the limit requires two signatures for one slot, which is proof, and the key is banned.

**XOR network coding.** It saves a transmission only when the relay can send one frame that both endpoints hear. Bluetooth L2CAP links are separate point-to-point connections, so relay B still sends once to A and once to C. Zero saving on this transport.

**DAG parent hashes as acknowledgements.** Mysticeti is a consensus protocol for blockchain validators, not a messaging acknowledgement scheme. Adding parent hashes means changing the signed core of every envelope and every test, to save an 8-byte ACK that only disappears when the recipient happens to reply.

**Longest-reach neighbour selection.** It prefers neighbours near the edge of range, which are the links most likely to drop. With `MAX_LINKS` of 5 and three phones it changes nothing.

**Dandelion++.** It protects against other participants guessing who originated a message from timing. It does not protect against someone listening to the radio, who still sees the sender transmit first. In this app every post is also signed by a persistent key, the `hops` field reads 0 at the first relay, and each phone continuously advertises its ID prefix. A three-node line has only one neighbour to "stem" to, so there is nothing to show.

**Jamming tomography.** Android gives apps no measurement of background radio energy. A run of timeouts on a link that had a strong signal also happens when the other phone's app is killed, its Bluetooth is switched off, or its owner walks behind a wall. What already exists is the true claim: dead links are routed around, and sync repairs what was missed.

---

## 4. What to say and what not to say

### Claims to drop

| Do not say | Why | Say instead |
|---|---|---|
| "800 m per hop" | Measured on nRF52840 development boards at +8 dBm in a BLE plus LoRa paper, not on phones | The distance you measured in U5, or "long-range mode is supported where the phone allows; not range-tested" |
| "We extract the spammer's secret key" | Not built and not sound without zero-knowledge proofs | "Breaking the rate limit forces two signatures for one slot. That is proof, and the key is banned" |
| "100% immune to jammers" | A 2.4 GHz jammer stops Bluetooth | "We need no cell tower or GPS. Against 2.4 GHz jamming we fall back to a 5 GHz hotspot, and to QR if that is built" |
| "Bluetooth LE hops 1,600 times per second" | That figure belongs to classic Bluetooth | "Bluetooth LE hops channel on every connection event across 37 data channels and avoids bad ones" |
| "Mysticeti DAG", "XOR network coding cuts hops in half" | Not built; do not apply to this transport | Leave out, or name as future work for broadcast radios |
| "Origin hiding against triangulation" | Dandelion++ does not do that against a radio listener | "Metadata is visible to relays and to radio listeners; that is a stated limitation" |

### Citations

- **Usable as future work, described accurately:** Rate-Limiting Nullifiers (Waku RLN-Relay, arXiv 2207.00116); Dandelion++ (Fanti et al., 2018); XOR coding for broadcast radios (COPE, Katti et al., 2006); Mixer (Herrmann, Mager, Zimmerling, 2018).
- **Usable with care:** arXiv 2604.15532 exists, but it describes dual-radio nodes with Bluetooth plus LoRa on development boards. Do not present it as evidence about phones.
- **Do not cite:** "Manta, arXiv 2610.34419" and "IEEE S&P 2026 formalization of Mysticeti". Neither could be found. Mysticeti itself is a real NDSS 2025 paper about blockchain consensus and is not what this project does.

### Theme

If the main track theme is "Let's Dig In", the TUNNEL profile and the relay chain A → B → C are the literal reading (a message passed hand to hand out of a collapsed tunnel), and working below the usual Bluetooth APIs at the socket level is the figurative one. A rename to "SubTerra" is cosmetic; do it only in the page title and Devpost text, not in package names.

---

## 5. Order of work and owners

Feature freeze stays at 8:00 AM Sunday.

| Order | Item | Owner | Budget | Start condition |
|---|---|---|---|---|
| 0 | Real-phone acceptance checks 1 to 6 | Android agent + human | until done | now |
| 0b | U11 benchmark (NAIVE and CURRENT columns) | core agent | 1 h | now, simulator only |
| 0c | U10 DM failover | core agent | 1.5 h | now, simulator only |
| 0d | U9 tree broadcast, then add the TREE column to U11 | core agent | 3.5 h | after 0b, simulator only |
| 1 | U1 statistics | core, UI | 1 h | item 0 passed |
| 2 | U7 DM length padding | core | 45 min | item 0 passed |
| 3 | U8 impersonation warning | core, UI | 45 min | item 0 passed |
| 4 | U2 panic wipe | core, UI | 2 h | item 2 done (uses the new DM layout) |
| 5 | U4 iPhone guest access (guide 5.13) | Android, UI | 2 h | item 0 passed |
| 6 | U3 mission profiles | core, UI | 1.5 h | item 4 done |
| 7 | U6 QR hop | Android, core | 3 h | everything above done by 2:00 AM |
| 8 | U5 long-range experiment | Android + human | 2 h hard limit | only with time to spare; it does not count toward the TDS prompt |
| 9 | Remaining guide stretch items (photo, notification) | any | rest | only if time |

Items 0b to 0d run on a branch while the phones are tested, and merge only after item 0 passes. Items 1 to 3 are independent and can run in parallel on separate agents.

**Switching phones to `BROADCAST_MODE = TREE`:** only after acceptance checks 1 to 6 pass again on the phones in `TREE` mode. If they do not, ship `FLOOD` on the phones and show `TREE` in the benchmark.

**Cut line at midnight:** if behind, drop in this order: U6, U3, U5, U2. Never drop item 0, U11 or U10.

Agent D updates `docs/THREATS.md`, `docs/DEMO.md` and `docs/NUMBERS.md` as each item lands, and removes any sentence from the Devpost draft that appears in the "Claims to drop" table.

### Demo script for the TDS judges (replaces the order in guide section 11)

Organise the three minutes around the three verbs in the prompt. Every step is a real action on real phones.

**Protects user privacy**

1. A sends a DM to C through B. C shows "Delivered, 2 hops via B". B's screen shows it relayed a message it cannot read.
2. State what a relay sees: sender, recipient, timing and a size bucket. Nothing else.

**Detects threats**

3. Turn on "Tamper relay" on B. A sends again. C rejects it, logs `BAD_SIGNATURE` against the link to B, and A is not blamed.
4. C renames itself to A's name. B, which verified A earlier, shows the impersonation warning.

**Mitigates attacks**

5. Press "Flood" on A. B produces the two conflicting signatures as proof, bans A's key, and C holds exactly the first three posts.
6. Show the Statistics table: drops by kind, duplicates dropped, delivery time.

**Efficiency (have it ready for the "does it spam everyone?" question)**

7. On the laptop, open `docs/NUMBERS.md`: transmissions per post and per DM for NAIVE, CURRENT and TREE.
8. On four desktop nodes in a diamond, turn on Drop on the relay A's route uses; A's DM is rerouted, delivered, and `ROUTE_FAILED` is logged.

**If time remains**

9. Panic wipe on A: C's conversation with A disappears and A returns with a new ID.
10. A judge joins from their own phone through the guest page.

Then state the limits before they ask: no forward secrecy, relays see who talks to whom, one attacker can create many identities and each one gets its own rate limit.
