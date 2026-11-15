# OfflineMesh demo

Two minutes per judge. The first 60 seconds are the core and must always run; the second minute is chosen from the extras. Three nodes: A sends, B relays, C receives.
Phones: A = OPPO Find X9 Pro, B = Galaxy Z Fold 7 (relay), C = Galaxy A52s. If the Fold 7 is not there, the laptop desktop node is the relay (that layout passed on real phones: laptop over LAN, A52s and OPPO over Bluetooth).
Two people: the **Driver** taps the phones, the **Speaker** talks and points. Solo: do both, and drive from the shell commands below if tapping is too slow.
Have the fallback (desktop) ready in a second terminal and the fallback video open in a tab before you start. All phones on chargers (the OPPO's battery saving stopped the app in the real-phone run).

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
4. Force two hops. On A: People tab, open C, switch on **Muted**. On C: People tab, open A, switch on **Muted**. (All phones on a desk are in range; muting is done in software. Say so.)
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

- **B verifies A** (the impersonation step needs it): on B's contacts table tick **Verified** for A, after comparing fingerprints.
- **Diamond on the laptop** (the failover step needs it). Four desktop nodes, A linked to B and C, both linked to D. Same core code, TCP instead of Bluetooth. From the repo root in Git Bash:
  ```
  ./gradlew :desktop:installDist -q
  X=desktop/build/install/desktop/bin/desktop; R=build/mesh-diamond; rm -rf $R; mkdir -p $R
  n() { $X --data $R/$1 --ui-port $2 --listen $3 --no-discovery "${@:4}" > $R/$1.log 2>&1 & echo $! > $R/$1.pid; }
  n A 8091 9101; n B 8092 9102 --peer 127.0.0.1:9101; n C 8093 9103 --peer 127.0.0.1:9101
  n D 8094 9104 --peer 127.0.0.1:9102 --peer 127.0.0.1:9103
  # stop later: for f in $R/*.pid; do kill $(cat $f); done
  ```
  Open `http://127.0.0.1:8091/` (A) and `:8094/` (D). Wait until A's People tab shows D at 2 hops (a few seconds). Tried on the laptop: A's route to D went via B; with Drop on B, A's DM reached D via C in about 5 s and A logged `ROUTE_FAILED`.
- **`docs/NUMBERS.md`** open in a laptop tab, scrolled to the forwarding benchmark (numbers step).

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

Where things are (map in `docs/UI_STRUCTURE.md`): the **Security screen** is Me tab, *Security and demo attacks* (two taps); a new event also shows as a strip under the top bar on every screen, and tapping it opens the log. **Statistics** and links are Me tab, *Network and statistics*. **Muted** and **Verified** are People tab, open the person.

Before each judge: A has no ban on B (restart the apps if a Flood ran in the last 10 minutes), Tamper is off on B, all three Security screens are open in the laptop tabs.

### The 60-second core (always)

| Time | Driver does | Speaker says (exact lines) |
|---|---|---|
| 0:00 | Holds up the three phones in airplane mode. | "Three phones, airplane mode, no internet, no server. The middle one is a stranger relaying for the other two." |
| 0:08 | A: Chats, pick C, send "meet at the gym". Point at C receiving it and A's status "Delivered, 2 hops via B". | "A's message hopped through B to C, and C's signed receipt came back the same way." |
| 0:20 | B: Chats (no A-to-C conversation), then Me, Network and statistics: `relayed` went up. | "B carried it but cannot read it. It is encrypted end to end and padded, so a one-letter and a fifty-letter message look the same size." |
| 0:30 | B: Security screen, tick **Tamper while relaying**. A sends C "second message". Point at C's Security screen: `BAD_SIGNATURE` against the link to B. Untick Tamper. | "Now B turns hostile and flips a bit in everything it forwards. C catches it and blames B's link, not A, because anyone can put A's name on a forgery." |
| 0:45 | A: Security screen, press **Flood**. Point at B: `RATE_EQUIVOCATION ... banned 10 min`, and C's feed shows only three posts. | "A tries to spam: four posts where the limit is three. Two signed posts for one slot is proof anyone can check, so B bans A's key." |
| 0:58 | | "Protect, detect, mitigate: all three, live, on phones with no network." |

After Flood, B drops everything signed by A's key for 10 minutes. Nothing in the second minute needs A to send through B.

### The second minute (pick by the judge's interest, about 60 s in total)

| Length | Driver does | Speaker says (exact lines) |
|---|---|---|
| 15 s | **Impersonation.** C changes its name to A's name. Point at B: C's row says "same name as a verified contact", `NAME_IMPERSONATION` in B's Security screen. Change it back. | "Names can be copied, keys cannot. B verified A in person, so it warns. It does not ban, because sharing a name is not proof." |
| 20 s | **Browser guest.** B: Guest mode on, the iPhone scans the QR on B's Me tab, enters the PIN, sends C a DM. C replies. | "The iPhone has no app. The page gives it its own key, so it signs and decrypts its own messages. It trusts B for the code, and we say so in our threat model." |
| 15 s | **Panic wipe.** C: Me tab, Panic wipe, type `WIPE`. Point at B: the conversation with C is gone, `PEER_REVOKED` logged; C has a new ID. | "If your phone is about to be taken, one word wipes it and asks your contacts to delete their copies. It is a request, not a guarantee." |
| 15 s | **Numbers slide** (below) or `docs/NUMBERS.md`, 30-in-a-room rows. | "In our simulator, running our real code with 30 phones in a room, a direct message costs about 5 transmissions; plain flooding costs about 98." |
| 15 s | **Failover**, laptop diamond (see prep): tick **Drop while relaying** on the node A's route to D uses; A DMs D; after about 5 s "Delivered ... via" the other node and `ROUTE_FAILED` on A. | "These are four desktop nodes running the same code over TCP. A relay drops the message, the receipt never comes, A resends on a backup route." |
| 10 s | Nothing to tap. Always end with this. | "What we don't stop: one person making many identities, and relays seeing who talks to whom. There's no forward secrecy, and nobody has audited it. It's all in THREATS.md." |

### If something fails live

Do not debug in front of a judge. Say one sentence and switch.

| What fails | Say | Switch to |
|---|---|---|
| A DM stays "Queued" for more than 10 s | "Bluetooth is being slow; let me show it on the same code over the laptop." | Laptop tabs on the desktop nodes (Path 3, started with `UI_BASE=8181` so they do not clash with the phones on 8081-8083) and run the same core there. |
| One phone dropped off (no links in Network) | "That phone's battery saver just put us to sleep, which is a real limit we found." | Wake it, check it is on the charger; if it does not relink in 15 s, use the laptop node as relay or Path 3. |
| A tap does nothing | (nothing) | The shell command for that step from "Driving it from the shell". |
| Flood ran earlier and A is banned on B | "A is still serving its 10-minute ban from the last demo, which is the point." | Show B's Security screen with the old ban, then do the core DM from C to A instead. |
| The guest page will not load on the iPhone | "Hotspot trouble; here it is in the laptop browser." | Open `http://<B's hotspot IP>:8090/` on the laptop, or skip the guest step. |
| Everything is down | "Here is the recorded run on the real phones." | The fallback video, then talk through the numbers slide. |

### Fallback video (record before the deadline)

- [ ] Under 3 minutes. Follow the 60-second core, then impersonation, guest, panic wipe and the limits line.
- [ ] Real phones on screen, airplane mode visible in the status bar. Say in the video where any step runs on the laptop.
- [ ] Laptop screen captured side by side (`scripts/booth.sh`) so the Security screens are readable.
- [ ] Every number spoken is in `docs/NUMBERS.md` and labelled simulator, emulator or real phone.
- [ ] End with the limits line, including "not audited".
- [ ] Upload as a **public** video (YouTube or Vimeo) hours before the deadline: uploads can be slow, and the Devpost video cannot change after the deadline.
- [ ] Open the link in a private window to check it plays without a login, then paste it into Devpost.
- [ ] Keep the file on the laptop too, for the "everything is down" case.

## Numbers slide

One slide. Every figure is copied from `docs/NUMBERS.md`; the label says where it was measured. Simulator = our real core code in a virtual-time network on the laptop.

| Figure | Where measured |
|---|---|
| A direct message costs **5.17** transmissions with our routing, **97.80** with plain flooding (30 phones in a room) | simulator |
| A public post: **66.2** sends with ours, **88.5** with plain flooding; delivery **0.993** against **0.983** (same room) | simulator |
| Tree broadcast: **36.2** sends per post, but delivery drops to **0.898**, so it is off by default | simulator |
| Relays dropping DMs (3 of 30): slowest 1% of DMs **14.2 s** without failover, **8.3 s** with it | simulator |
| One post on a 25-node mesh with 40 links: **40 to 42** transmissions, about one per link | simulator |
| All 8 acceptance checks pass on **4 Android emulators** over emulated Bluetooth, release APK **2.45 MB** | emulator |
| A browser guest's DM crossed **3 hops** and was delivered | emulator |
| A **59,990-byte** frame over Bluetooth L2CAP in **4 to 6 s** | emulator |
| One Bluetooth DM between two real phones: **560 ms**; **6 of 7** checks passed (the 7th failed when the OPPO's battery saving stopped the app) | real phone |
| A post's proof of work: **6 to 9 ms** on one laptop core | laptop |
| **146** automated tests; **14** of them reproduce published attacks on other mesh apps (T1-T8, T11) | tests |

Do not add phone PoW times or Bluetooth range: they have not been measured.

## Driving it from the shell (if a tap fails)

```
curl -s 127.0.0.1:8081/api/snapshot | jq -r .self.id          # A's id; same for 8082, 8083
curl -s -X POST 127.0.0.1:8081/api/cmd -d '{"t":"dm","to":"<C id>","text":"meet at the gym"}'
curl -s -X POST 127.0.0.1:8082/api/cmd -d '{"t":"debug","flag":"tamper","on":true}'
curl -s -X POST 127.0.0.1:8081/api/cmd -d '{"t":"post","text":"C, are you there?"}'
curl -s -X POST 127.0.0.1:8081/api/cmd -d '{"t":"attack","kind":"flood"}'
curl -s -X POST 127.0.0.1:8081/api/cmd -d '{"t":"mute","id":"<C id>","on":true}'
curl -s -X POST 127.0.0.1:8083/api/cmd -d '{"t":"name","name":"<A name>"}'      # impersonation
curl -s 127.0.0.1:8082/api/stats                                                  # statistics
curl -s -X POST 127.0.0.1:8092/api/cmd -d '{"t":"debug","flag":"drop","on":true}' # failover, on the relay A's route uses
curl -s -X POST 127.0.0.1:8083/api/cmd -d '{"t":"panic","confirm":"WIPE"}'        # panic wipe
```
Use `127.0.0.1`, not `localhost` (on this laptop `localhost` can reach another server over IPv6).

## Optional extras (only if time is left)

- **Guest join** (prep for the second-minute guest step): B turns on its hotspot (16 or more random characters as the Wi-Fi password, not the guest PIN) and Guest mode. The iPhone joins the hotspot, scans the QR on B's Me tab and opens the full web client in Safari with its own key. `http://<B's hotspot IP>:8090/lite` is the old read-only page, whose posts show as "guest of B". The Me tab of the web client also offers the APK download with its SHA-256.
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
| Did you test it on real phones? | Say what is true on the day. As of writing: two real phones and a laptop node passed six of seven checks; the seventh failed because the OPPO's battery saving stopped the app, so we added a battery prompt and keep the phones on chargers. All eight checks pass on desktop nodes and on four Android emulators over emulated Bluetooth. Range and phone proof-of-work time are not measured. |
| Has it been audited? | No. Nobody outside the team has reviewed it. Do not rely on it where your safety depends on it. |
