# OfflineMesh demo

Under 3 minutes. Three nodes: A sends, B relays, C receives.
Phones: A = OPPO Find X9 Pro, B = Galaxy Z Fold 7 (relay), C = Galaxy A52s.
Have the fallback (desktop) ready in a second terminal before you start.

## Before the judges arrive (10 minutes)

### Path 1: real phones

1. All three phones: airplane mode on, Bluetooth on, mobile data off, Wi-Fi off.
2. Plug all three into the laptop, then:
   ```
   ./gradlew :app:assembleDebug
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
5. Dry run once: `scripts/e2e.sh 8081 8082 8083` must print PASS for items 1 to 6. It bans A on B for 10 minutes at the end, so run it at least 10 minutes before the demo, or restart the apps after it.

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
scripts/e2e.sh 8081 8082 8083       # optional check, all 6 PASS
scripts/run-3-desktop.sh stop
```
Same browser tabs, same script below. Tell judges it is TCP on one laptop instead of Bluetooth. Bluetooth on/off in step 4 becomes ticking **Muted** for B on C (and C on B), then unticking.

## The script

| Time | Do | Say |
|---|---|---|
| 0:00 | Show the three phones in airplane mode. Open A's Network tab. | "No internet, no server. Phones talk over Bluetooth. A sees C at 2 hops via B." |
| 0:20 | A: Messages, pick C, send "meet at the gym". | "This is end-to-end encrypted. B carries it but cannot read it." |
| 0:35 | Show C has it. Show A: "Delivered, 2 hops via B". Show B's Messages tab is empty. | "C's signed receipt came back the same way." |
| 0:55 | B: Security tab, tick **Tamper while relaying**. A sends C another DM. | "B now flips one bit in everything it forwards." |
| 1:10 | Show C's Security tab: `BAD_SIGNATURE` line. Untick Tamper on B. | "C rejects it. B's link is blamed, not A. A is not banned." |
| 1:25 | Cut C off: turn Bluetooth off on C (below). A posts "C, are you there?". | "C is out of range." |
| 1:40 | Turn Bluetooth back on on C. Wait up to 20 s. The post appears on C. | "When C comes back, the phones compare lists and fill the gap." |
| 2:05 | A: Security tab, press **Flood**. | "A tries 4 posts in one 10-second window. The limit is 3." |
| 2:15 | Show B's Security tab: `RATE_EQUIVOCATION ... banned 10 min`. Show C's feed: only the first three. | "Two signed posts for one slot is proof. B bans A's key and stops relaying it." |
| 2:35 | One sentence on limits. | "We don't stop one person making many identities, relays see who talks to whom, and there is no forward secrecy. It's all in THREATS.md." |

Flood comes last on purpose: it bans A on B for 10 minutes, so nothing from A crosses B after it.

Bluetooth off and on for C without touching the phone:
```
adb -s <serial-of-C> shell cmd bluetooth_manager disable
adb -s <serial-of-C> shell cmd bluetooth_manager enable
```
Relink takes about 4 s (measured on emulators); sync then runs at link-up.

## Driving it from the shell (if a tap fails)

```
curl -s 127.0.0.1:8081/api/snapshot | jq -r .self.id          # A's id; same for 8082, 8083
curl -s -X POST 127.0.0.1:8081/api/cmd -d '{"t":"dm","to":"<C id>","text":"meet at the gym"}'
curl -s -X POST 127.0.0.1:8082/api/cmd -d '{"t":"debug","flag":"tamper","on":true}'
curl -s -X POST 127.0.0.1:8081/api/cmd -d '{"t":"post","text":"C, are you there?"}'
curl -s -X POST 127.0.0.1:8081/api/cmd -d '{"t":"attack","kind":"flood"}'
curl -s -X POST 127.0.0.1:8081/api/cmd -d '{"t":"mute","id":"<C id>","on":true}'
```
Use `127.0.0.1`, not `localhost` (on this laptop `localhost` can reach another server over IPv6).

## Optional extras (only if time is left)

- **Guest join** (only once the app's Guest mode toggle exists; at the time of writing the server side is done, the app toggle is not): B turns on its hotspot and Guest mode; the iPhone scans the QR on B's Network tab and posts from Safari. The post shows as "guest of B".
- **Photo DM:** A sends C a photo (downscaled to under 40 KB, one frame).

## Judge questions

| Question | Answer |
|---|---|
| How do you stop mass-message spam? | Three layers. Each link has a token bucket, and a link that misbehaves is closed. Public posts are limited to 3 per 10 seconds per key; breaking that means signing two posts for one slot, which is proof anyone can check, and the key is banned. Proof of work adds a small cost per message. It does not stop a determined attacker by itself. |
| How do you stop malicious people joining or spoofing? | Anyone can join; it is an open emergency network. Nobody can impersonate an existing identity: the ID is a hash of the public key and every message is signed. People confirm contacts by comparing fingerprints in person. We do not stop one attacker creating many identities. |
| How do you stop a message reaching a million people? | DMs follow one path, not a flood. Broadcasts are forwarded once per link, never back to a neighbour that has them, and stop after 6 hops. In our simulator one post on 40 links cost 41 transmissions. Cost grows with nearby links, not network size. |
| How do you keep privacy and integrity across relays you do not trust? | DMs are encrypted end to end with AES-256-GCM using a key only the two endpoints can derive. Every message is signed, and its ID is a hash of its content, so any change is caught at the next honest node. |
| Did you test it on real phones? | Say what is true on the day. As of writing: three desktop nodes and three Android emulators over emulated Bluetooth pass all six checks; real phones not yet. |
