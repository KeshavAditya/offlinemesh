# OfflineMesh demo

Under 3 minutes. Three nodes: A sends, B relays, C receives. Steps 7 and 8 use the laptop (benchmark table and four desktop nodes).
Phones: A = OPPO Find X9 Pro, B = Galaxy Z Fold 7 (relay), C = Galaxy A52s.
Have the fallback (desktop) ready in a second terminal before you start.

## Before the judges arrive (10 minutes)

### Path 1: real phones

1. All three phones: airplane mode on, Bluetooth on, mobile data off, Wi-Fi off.
2. Plug all three into the laptop, then:
   ```
   ./gradlew :app:assembleRelease   # R8-shrunk, ~2.4 MB
   scripts/deploy-all.sh          # install, grant permissions, Bluetooth on, launch
   scripts/forward-ui.sh          # prints "<serial> -> http://127.0.0.1:808N/"
   ```
   `forward-ui.sh` numbers phones in `adb devices` order, not by role. Read its output. If the order is wrong, forward by hand:
   ```
   adb -s <serial-of-A> forward tcp:8081 tcp:8080
   adb -s <serial-of-B> forward tcp:8082 tcp:8080
   adb -s <serial-of-C> forward tcp:8083 tcp:8080
   ```
3. Open `http://127.0.0.1:8081/`, `:8082/`, `:8083/` side by side in the laptop browser for the projector.
4. Force two hops. On A's Network tab tick **Muted** for C. On C's Network tab tick **Muted** for A. (All phones on a desk are in range; muting is done in software. Say so.)
5. Dry run once: `scripts/e2e.sh 8081 8082 8083` must print PASS for items 1 to 7 (7 is the group check). It bans A on B for 10 minutes at the end, so run it at least 10 minutes before the demo, or restart the apps after it.

### Path 2: emulators (practice only)

Start them in this order, because only one Bluetooth simulator (netsimd) may run:
```
emulator -avd meshA -no-snapshot-save &
# wait until %TEMP%/netsim.ini exists
emulator -avd meshB -no-snapshot-save &
emulator -avd meshC -no-snapshot-save &
```
Then the same `deploy-all.sh`, `forward-ui.sh`, `e2e.sh` as for phones (serials emulator-5554/5556/5558 map to 8081/8082/8083).

### Path 3: fallback, three desktop nodes (if phone Bluetooth fails)

```
scripts/run-3-desktop.sh            # A <-> B <-> C over TCP, UIs on 8081..8083
scripts/e2e.sh 8081 8082 8083       # optional check, items 1-7 PASS
scripts/run-3-desktop.sh stop
NODES=5 scripts/run-3-desktop.sh    # optional: 5 nodes A..E in a line, UIs on 8081..8085
```
Same browser tabs, same script below. Tell judges it is TCP on one laptop instead of Bluetooth. Bluetooth off and on in the catch-up extra becomes ticking **Muted** for B on C (and C on B), then unticking.

### Extra prep for the v2 script

- **B verifies A** (step 4 needs it): on B's contacts table tick **Verified** for A, after comparing fingerprints.
- **Diamond on the laptop** (step 8 needs it). Four desktop nodes, A linked to B and C, both linked to D. Same core code, TCP instead of Bluetooth. From the repo root in Git Bash:
  ```
  ./gradlew :desktop:installDist -q
  X=desktop/build/install/desktop/bin/desktop; R=build/mesh-diamond; rm -rf $R; mkdir -p $R
  n() { $X --data $R/$1 --ui-port $2 --listen $3 --no-discovery "${@:4}" > $R/$1.log 2>&1 & echo $! > $R/$1.pid; }
  n A 8091 9101; n B 8092 9102 --peer 127.0.0.1:9101; n C 8093 9103 --peer 127.0.0.1:9101
  n D 8094 9104 --peer 127.0.0.1:9102 --peer 127.0.0.1:9103
  # stop later: for f in $R/*.pid; do kill $(cat $f); done
  ```
  Open `http://127.0.0.1:8091/` (A) and `:8094/` (D). Wait until A's Network tab shows D at 2 hops (a few seconds). Tried on the laptop: A's route to D went via B; with Drop on B, A's DM reached D via C in about 5 s and A logged `ROUTE_FAILED`.
- **`docs/NUMBERS.md`** open in a laptop tab, scrolled to the forwarding benchmark (step 7).

### Booth setup

- **Poster:** `docs/poster.pdf` (A2 landscape, one page; prints to A3 with "fit to page"). To change it, edit `docs/poster.html` (numbers come only from `docs/NUMBERS.md`) and re-export: open it in Chrome or Edge, Print, Save as PDF, margins None, background graphics on.
- **Three screens on the laptop:** `scripts/booth.sh` forwards the phones and opens `scripts/booth.html`, which shows the three node UIs side by side, labelled A, B relay, C.
  ```
  scripts/booth.sh <serial-of-A> <serial-of-B> <serial-of-C>   # phones in role order -> 8081, 8082, 8083
  scripts/booth.sh                                              # all phones in adb order (forward-ui.sh)
  scripts/booth.sh 8181 8182 8183                               # ports only, e.g. UI_BASE=8181 scripts/run-3-desktop.sh
  ```
  Each label links to that node's full page. Press F11 in the browser for full screen.

## The script (TDS: Privacy and Security)

Organised by the three verbs of the prompt. Steps 1 to 6 are real actions on the phones (or the 3 desktop nodes in the fallback). **Steps 7 and 8 run on the laptop**: step 7 is the simulator benchmark table, step 8 is four desktop nodes. Say so when you get there.

| Time | Do | Say |
|---|---|---|
| | **Protects user privacy** | |
| 0:00 | 1. Show the three phones in airplane mode. A: Chats, pick C, send "meet at the gym". Show C has it, A shows "Delivered, 2 hops via B". On B: Chats is empty, and in Statistics `relayed` went up. | "No internet, no server. B carried it but cannot read it. C's signed receipt came back the same way." |
| 0:25 | 2. Nothing to tap. | "A relay sees sender, recipient, timing and a size bucket. Nothing else. A one-letter and a fifty-letter message are the same size on the air." |
| 0:35 | *Optional, cut if short on time.* B: Chats, **New group**, tick every contact, send "group test". Show B: "Delivered to 2 of 2". | "A group message is one encrypted DM to each member. Relays still see only DMs." |
| | **Detects threats** | |
| 0:55 | 3. B: Security tab, tick **Tamper while relaying**. A sends C another DM. Show C's Security tab: `BAD_SIGNATURE` against the link to B. Untick Tamper. | "B flips one bit in everything it forwards. C rejects it and blames B's link, not A." |
| 1:15 | 4. C changes its name to A's name. Show B: C's row says "same name as a verified contact", and `NAME_IMPERSONATION` is in B's Security tab. Change C's name back. | "Names can be copied, keys cannot. B verified A earlier, so it warns. It does not ban: sharing a name is not proof." |
| | **Mitigates attacks** | |
| 1:35 | 5. A: Security tab, press **Flood**. Show B: `RATE_EQUIVOCATION ... banned 10 min`. Show C's feed: only the first three. | "Four posts in one 10-second window; the limit is three. Two signed posts for one slot is proof. B bans A's key." |
| 1:55 | 6. Show the Statistics table on B and C: drops by kind, duplicates dropped, delivery time. | "Every number here is counted by the code as it runs." |
| | **Efficiency** (for "does it just spam everyone?") | |
| 2:10 | 7. Laptop: `docs/NUMBERS.md`, forwarding benchmark, 30-in-a-room rows. | "This is the simulator running our real core code. Plain flooding is NAIVE; CURRENT is what ships. TREE halves the sends but loses delivery, so it is off by default." |
| 2:25 | 8. Laptop diamond: on A's Network tab see which of B or C its route to D uses; tick **Drop while relaying** on that node. A DMs D. After about 5 s A shows "Delivered, 2 hops via" the other node (the status line says "rerouted via" while it is live), and `ROUTE_FAILED` is in A's Security tab. | "These are four desktop nodes, same code over TCP; three phones in a line have no second path. No receipt, so A resent on a backup route." |
| 2:45 | One sentence on limits. | "We don't stop one person making many identities, relays see who talks to whom, and there is no forward secrecy. It's all in THREATS.md." |

If you cut the group step, you have 20 s spare for questions. Either way the script ends before 3:00.

Flood (step 5) bans A on B for 10 minutes, and B drops everything signed by A's key after it. So nothing from A crosses B after step 5. That is why B, not A, creates the group, and why the panic wipe below is done on C.

**If time remains**

9. Panic wipe on C: Network tab, **Panic wipe**, type `WIPE`. A's conversation with C disappears, A's Security tab logs `PEER_REVOKED`, and C comes back with a new ID. (Not on A: A's revoke would be dropped by B because of the ban.)
10. A judge joins from their own phone through the guest page (see Optional extras).

## Driving it from the shell (if a tap fails)

```
curl -s 127.0.0.1:8081/api/snapshot | jq -r .self.id          # A's id; same for 8082, 8083
curl -s -X POST 127.0.0.1:8081/api/cmd -d '{"t":"dm","to":"<C id>","text":"meet at the gym"}'
curl -s -X POST 127.0.0.1:8082/api/cmd -d '{"t":"debug","flag":"tamper","on":true}'
curl -s -X POST 127.0.0.1:8081/api/cmd -d '{"t":"post","text":"C, are you there?"}'
curl -s -X POST 127.0.0.1:8081/api/cmd -d '{"t":"attack","kind":"flood"}'
curl -s -X POST 127.0.0.1:8081/api/cmd -d '{"t":"mute","id":"<C id>","on":true}'
curl -s -X POST 127.0.0.1:8083/api/cmd -d '{"t":"name","name":"<A name>"}'      # step 4
curl -s 127.0.0.1:8082/api/stats                                                  # step 6
curl -s -X POST 127.0.0.1:8092/api/cmd -d '{"t":"debug","flag":"drop","on":true}' # step 8, on the relay A's route uses
curl -s -X POST 127.0.0.1:8083/api/cmd -d '{"t":"panic","confirm":"WIPE"}'        # step 9
```
Use `127.0.0.1`, not `localhost` (on this laptop `localhost` can reach another server over IPv6).

## Optional extras (only if time is left)

- **Guest join**: B turns on its hotspot and Guest mode; the iPhone scans the QR on B's Network tab and posts from Safari. The post shows as "guest of B".
- **Photo DM:** A sends C a photo (downscaled to under 40 KB, one frame).
- **Catch-up after reconnect:** turn Bluetooth off on C, A posts "C, are you there?", turn Bluetooth back on. The post appears on C within about 20 s. Without touching the phone:
  ```
  adb -s <serial-of-C> shell cmd bluetooth_manager disable
  adb -s <serial-of-C> shell cmd bluetooth_manager enable
  ```
  Relink takes about 4 s (measured on emulators); sync then runs at link-up.

## Judge questions

| Question | Answer |
|---|---|
| How do you stop mass-message spam? | Three layers. Each link has a token bucket, and a link that misbehaves is closed. Public posts are limited to 3 per 10 seconds per key; breaking that means signing two posts for one slot, which is proof anyone can check, and the key is banned. Proof of work adds a small cost per message. It does not stop a determined attacker by itself. |
| How do you stop malicious people joining or spoofing? | Anyone can join; it is an open emergency network. Nobody can impersonate an existing identity: the ID is a hash of the public key and every message is signed. People confirm contacts by comparing fingerprints in person. We do not stop one attacker creating many identities. |
| Does it just spam messages to everyone? | No. DMs are routed: each phone learns from signed beacons which neighbour leads to each person, so a DM takes one path. In the simulator with 30 phones in a room, a delivered DM cost 5.17 DM transmissions with our routing against 97.80 for plain flooding, and a post cost 66.2 sends against 88.5. A tree broadcast mode cuts posts to 36.2 sends, but delivery drops from 0.993 to 0.898, so it is off by default. If a relay drops DMs, the sender notices the missing receipt and reroutes. All figures: `docs/NUMBERS.md`. |
| How do you stop a message reaching a million people? | DMs follow one path, not a flood. Broadcasts are forwarded once per link, never back to a neighbour that has them, and stop after 6 hops. In our simulator one post on 40 links cost 41 transmissions. Cost grows with nearby links, not network size. |
| How do you keep privacy and integrity across relays you do not trust? | DMs are encrypted end to end with AES-256-GCM using a key only the two endpoints can derive. Every message is signed, and its ID is a hash of its content, so any change is caught at the next honest node. |
| Does it work with more than three phones? | Yes. Each phone keeps up to 5 direct links and more phones form more hops. We ran 5 desktop nodes end to end, and the simulator runs 25 and 50. A group message costs one DM per other member, so groups are capped at 16. |
| Did you test it on real phones? | Say what is true on the day. As of writing: three desktop nodes and three Android emulators over emulated Bluetooth pass all six checks; real phones not yet. The v2 features (padding, impersonation warning, failover, panic wipe, profiles, tree mode) are tested in the simulator, on desktop nodes and emulators, not on phones. |
