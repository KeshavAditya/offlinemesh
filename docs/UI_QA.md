# UI QA: redesigned page (ui-modern merged into v2)

Independent QA on branch `ui-qa-fixes` = `origin/v2` + `origin/ui-modern` (4a238d1), 2026-11-15. Three real desktop nodes in a line A-B-C (UI 8281-8283, guest server on A at 8291), driven through the real page in headless Chromium (playwright-core), results confirmed on the other nodes through their pages or `/api/snapshot`.

**86 checks: 80 pass, 5 failed and fixed, 1 open.** Screenshots: `.playwright-mcp/ui-qa/` in the main project folder (not in git).

## Fix commits

| Commit | What | Why |
|---|---|---|
| 2dc9ddd | chat row time is `flex: none` | at 320 px a long title wrapped the time onto two lines |
| e5bbfe0 | Edit members chevron turns down when open | open and closed looked the same |
| 4c7e3f6 | group text box on its own row below 360 px | I'm safe + Photo + Send left it about 30 px |
| 0c4ef35 | "You are no longer in this group" | there is no leave action; members are removed by the owner |
| 6cbe065 | map names beside the node when its links run up and down | the link line struck through every name in a line of nodes |
| 944ea15 | wiped identity not listed under "In range, no chat yet" | it looked like a person you could message |

## Checks

| Check | Result | Evidence | Fix commit or "open" |
|---|---|---|---|
| first-run name (welcome dialog sets name on A) | PASS | dialog open=true, fp shown="7989 3a11 d233 7016", snapshot self.name=Alice |  |
| welcome: Escape counts as seen (no dialog after reload) | PASS | reloaded after Escape |  |
| names propagate to A (Bob, Carol via Enter in dialog) | PASS | [["Bob",1],["Carol",2]] |  |
| welcome not shown again on A after reload | PASS | reload |  |
| public post A appears once on B and C | PASS | counts A/B/C=1/1/1 |  |
| post visible in B page feed | PASS | Alice · 7989 / Hello from Alice, public / 01:09 · 1 hop |  |
| feed unread badge on C tab bar | PASS | n-feed=1 |  |
| DM A->C shows on C | PASS | [["secret for Carol 4711","RECEIVED",2]] |  |
| DM A->C: A shows "Delivered · 2 hops via Bob" | PASS | secret for Carol 4711 / 01:09 · Delivered · 2 hops via Bob · 936c |  |
| DM A->C: B shows nothing readable | PASS | B snapshot has no "4711"; B dms keys=0 |  |
| C unread badge for DM | PASS | n-chats=1 |  |
| reply C->A shows in A open conversation | PASS | secret for Carol 4711 / 01:09 · Delivered · 2 hops via Bob · 936c / reply from Carol / 01:09 · 2 hops via Bob · 936c |  |
| photo DM A->C renders on C | PASS | img naturalWidth on C=640; A dmErr="" |  |
| create group A,B,C from New chat > New group | PASS | A members=3, on B=true, on C=true; A screen after create=chats |  |
| group message: "Delivered to 2 of 2" on A | PASS | group hello 1 / 01:09 · Delivered to 2 of 2 |  |
| group message arrives on B and C | PASS | gdms on B and C |  |
| group photo A -> B and C | PASS | img in gdms of B and C=true; A dmErr="" |  |
| "I'm safe" roll call and roster | PASS | AliceYouOwner / safe at 01:11 / Bob · 936c / safe at 01:11 / Carol · e82a / no "I'm safe" yet |  |
| "I'm safe" with note shows on C | PASS |  / Alice · 7989 / 01:09 · 2 hops via Bob · 936c / Alice · 7989 / I'm safe: at the bridge / 01:11 · 2 hops via Bob · 936c / Bob · 936c / I'm safe / 01:11 · 1 hop raw=["✅ S |  |
| owner removes a member; removed member gets no further messages | PASS | A members=2:true; C got it=false; A sees: 1 · 1 hop / after removal, not for Carol / 01:11 · Delivered to 1 of 1; C group state=[] | 0c4ef35 |
| verify a contact (switch) and verified badge | PASS | snapshot verified=true; info badge=true; People: Carol · e82a; Alice [vf] |  |
| verified-only feed filter | PASS | off: Alice / Hello from Alice, public / 01:09 · 1 hop / Pin / Carol · e82a / Carol unverified post / 01:11 · 1 hop // on: Alice / Hello from Alice, public / 01:09 · 1 hop |  |
| verified-only filter persists across reload | PASS | checked after reload=true |  |
| pin a verified post in Feed | PASS | AlicePinned / Hello from Alice, public / 01:09 · 1 hop / Unpin |  |
| impersonation warning (C takes A's verified name on B) | PASS | suspect=true; People: Alice · e82a / 1 hop · linked / Same name as a verified contact / Alice / 1 hop · linked; info: Same name as a verified contact; chat header: Same n |  |
| impersonation flag clears after rename back | PASS | suspect=false |  |
| mute switch and Muted tag | PASS | snapshot muted=true; People: Bob · 936c / 1 hop · linked / Carol · e82aMuted / 2 hops via Bob · 936c · not linked |  |
| search in Chats (by title, by last message text, no match) | PASS | "resc": Rescue team / 01:11 / You: after removal, not for Carol // "4711": No chat or person matches "4711". // "zzzz": No chat or person matches "zzzz". |  |
| profile switch and its values | PASS | STANDARD: presenceMs 30000 / syncMs 15000 / jitterMinMs 30 / jitterMaxMs 100 / maxLinks 5 / relaySto // TUNNEL: presenceMs 15000 / syncMs 5000 / jitterMinMs 0 / jitterMax |  |
| statistics change over time on the open page | PASS | before: originated 15 / relayed 0 / duplicatesDropped 0 / forwardsSkipped 0 / bytesOut 3 // after: originated 15 / relayed 0 / duplicatesDropped 0 / forwardsSkipped 0 / b |  |
| mesh map shows three nodes and links | PASS | {"nodes":3,"lines":2,"labels":["You","1","Bob · 936c","2","Carol · e82a"],"n":"2 reachable"} | 6cbe065 |
| browser Back closes pushed screens one at a time | PASS | info/2 -> convo/1 -> chats/0 |  |
| header Back button closes pushed screens; hidden on a tab | PASS | convo/1 -> chats/0, back hidden=true |  |
| Info > Message returns to the same chat (no stacked copy) | PASS | convo/1 |  |
| New chat > person opens the chat; Back lands on Chats | PASS | convo/1 -> chats/0 |  |
| omBack() (Android system Back): pops a pushed screen, false on a tab | PASS | pushed=true, on tab=false, now people/0 |  |
| reload on a pushed screen: lands on the tab, page usable | PASS | after reload chats/0, back hidden=true; browser Back x1 -> chats/0 (http://127.0.0.1:8281/); x2 -> chats/0 |  |
| tab switch from a pushed screen resets to the tab | PASS | feed/0; then browser Back -> feed/0 |  |
| late "I'm safe" alert after N minutes (info + chat header) | PASS | info: No "I'm safe" for 1 min from you, Bartholomew Maximilian Longnames · 936c // header: No "I'm safe" for 1 min from you, Bartholomew Maximilian Longnames · 936c |  |
| Copy fingerprint gives feedback | PASS | button text=Copied |  |
| keyboard focus ring visible | PASS | BUTTON# outline=solid 2px |  |
| Android-only rows hidden without the bridge | PASS | #nat hidden=true, page bundle section shown=true |  |
| Android rows shown and call the bridge with a stub | PASS | rows: Bluetooth / Wi-Fi LAN / Guest mode / Block screenshots / Inv...; initial ble=true,lan=false,guest=false,secure=false; calls: set lan true; set secure true; open inv |  |
| omNative(): switch follows the app, blocker text shows in the bar | PASS | bar="Turn on Bluetooth.", ble switch=false |  |
| reconnecting bar shows while the node is down and clears after | PASS | bar="Reconnecting…", cleared after restart=true, C page shows chats=true |  |
| DM to an offline peer: pending state shown, delivered after it returns | PASS | while offline: · 2 hops / unread ping 1280l / 01:18 · 2 hops / sent while Carol is offline / 01:26 · Sent // after:  Carol is offline / 01:26 · Delivered · 2 hops via Bar |  |
| guest: wrong PIN refused | PASS | No links, "Reconnecting…", "Can't join the host. If its PIN changed, enter the new PIN." and a PIN box (40-guest-wrong-pin-390.png) |  |
| guest: PIN unchanged after one wrong try | PASS | 1139 -> 1139 |  |
| guest: join with a name | PASS | welcome dialog=true; guest id bc9e3fe4dacc8fc4; C sees ["Gus Visitor",3] |  |
| guest: sees the feed (older posts sync in) | PASS | Alice · 7989 / Hello from Alice, public / 01:29 · 1 hop / Carol · e82a / Carol unverified post / 01:29 · 3 hops via Alice · 7989 / Bob · 936c / stats tick / 01: |  |
| guest: post reaches A and C under the guest's own key | PASS | C feed has it from bc9e3fe4dacc8fc4: true; guest postErr="" |  |
| guest: DM node A and get a reply | PASS | welcome, guest / 01:29 · 1 hop / hello Alice from guest / 01:29 · Delivered · 1 hop |  |
| guest: DM node C across hops with a receipt | PASS | guest shows: hello Carol across the mesh / 01:29 · Delivered · 3 hops via Alice · 7989; C has it=true |  |
| guest: added to a group, receives and sends there | PASS | group listed=true; got msg=true; A got reply=true; A sees sage for the guest / 01:29 · Delivered to 2 of 2 / Gus Visitor · bc9e / guest replies in group / 01:29 · 1 hop |  |
| guest: "Get the app" link present | PASS | ["Download OfflineMesh.apk -> /app.apk"] ; details summary: Get the Android app,Edit members |  |
| guest: demo attack console and native rows hidden | PASS | {"sec":"Security log","attacksHidden":true,"nat":true,"bundleHidden":true} |  |
| guest: reload keeps identity | PASS | {"id":"bc9e3fe4dacc8fc4","name":"Gus Visitor","welcome":false,"convos":"With guest / 01:29 / You: guest replies in group / Carol · e82a / 01:29 / You: hello Carol across  |  |
| guest: history survives reload | PASS | With guest / 01:29 / You: guest replies in group / Carol · e82a / 01:29 / You: hello Carol across the mesh / Alice · 7989 / 01:29 / You: hello Alice f |  |
| Tamper on B, A posts: C logs the rejection, A is not banned | PASS | tamper pressed=true; C event={"tsMs":1794727851420,"kind":"BAD_SIGNATURE","detail":"msg a12168b21b19b689","link":"lan1","src":"79893a11d2337016"}; C has tampered post=0;  |  |
| security alert bar + Me badge on C; tapping it opens the log; counters update | PASS | alert="BAD_SIGNATUREmsg a12168b21b19b689"; badge=1; log: BAD_SIGNATURE / 01:30:51 / msg a12168b21b19b689 src=Alice · 7989 link=lan1; counter: Bad signature (spoof, tamper |  |
| after Tamper off, A's next post reaches C (A not banned) | PASS | pressed=false; C got it=true; the probe post on C now=0 |  |
| Replay button produces its log line / counter | PASS | B new events=[]; B duplicatesDropped 0->1; A log: BAD_SIGNATURE / 01:30:52 / msg 6104818fe05c0ce9 src=Carol · e82a link=lan1 |  |
| Spoof button produces its log line (B: BAD_SIGNATURE) | PASS | B event={"tsMs":1794727859104,"kind":"BAD_SIGNATURE","detail":"msg c68ffac31ccc959f","link":"lan4","src":"936cd38f1ded38cd"}; A log: BAD_SIGNATURE / 01:30:52 / msg 610481 |  |
| Flood on A: B bans A, C holds exactly three | PASS | B banned A=true; C feed grew by 3 |  |
| counters and banned list update on B | PASS | banned list: Alice · 7989 / 7989 3a11 d233 7016 / Banned until 01:41 (count 1); counters: Bad signature (spoof, tamper) 1 / Double post, sender banned (flood) 1 / Over th |  |
| panic wipe needs the exact word WIPE | PASS | typed "wipe": id unchanged=true |  |
| panic wipe on C: C has a new ID | PASS | e82adb4eae27de7d -> f6c42c3d34bc62aa; C dms=0, feed=11, name=node-f6c4 |  |
| panic wipe on C: A's conversation with C disappears | PASS | A had 10 messages with C; after the wipe A's dms has no entry for C and the chat row is gone (61-A-chats-after-C-wipe-390.png). The old ID stayed listed as "in range": fi | 944ea15 |
| after the wipe C's page shows the first-run dialog with the new ID | PASS | welcome open=true; page self.id=f6c42c3d34bc62aa; fingerprint shown=f6c4 2c3d 34bc 62aa |  |
| wiped identity not offered under "In range" on Chats; People labels it Revoked | PASS | revoked on A: Carol e82a; Chats:  / You: after removal, not for Carol / In range, no chat yet / Bob · 936c / 1 hop / Carol · f6c4 / 2 hops via Bob · 936c; People: Carol · | 944ea15 |
| looks: every screen at 390 light (25 viewport shots, scroll positions) | PASS | 390l-*.png; no horizontal scroll, one 16 px left edge, last item reachable above the tab bar (24 px clear) |  |
| looks: every screen at 390 dark | PASS | 390d-*.png; text, switches, QR on white, map halo all readable |  |
| looks: every screen at 320 light | PASS after fixes | 320l-*.png; two bugs found there, see fixes 2dc9ddd and 4c7e3f6 (fix-320-*.png) | 2dc9ddd, 4c7e3f6 |
| looks: every screen at 1280 light | PASS | 1280l-*.png; 680 px centred column, tabs move to the top |  |
| looks: tab bar over long pages (Me, Network, Security) | PASS | only a full-page capture artifact: the bar is fixed, content scrolls under it, Wi-Fi LAN switch and Panic wipe are reachable (390l-me-native-s2/s3.png) |  |
| looks: long names (32 bytes) and long unbroken messages | PASS | names wrap or ellipsize, bubbles break long words, no sideways scroll (390l-dm.png, 390l-people.png) |  |
| looks: empty states (Chats, Feed, People, Network, conversation, search, security) | PASS | 70-empty-*.png, 25-A-search-empty-390.png, 24-B-chat-impersonator-390.png |  |
| looks: chat row time at 320 with a long title | FAIL, fixed | time wrapped to "01:1 / 8" (320l-chats.png); after: fix-320-chats.png | 2dc9ddd |
| looks: group compose bar at 320 | FAIL, fixed | text box about 30 px wide (320l-group-longname.png); after: own row, 288 px (71-final-320-group.png) | 4c7e3f6 |
| looks: Edit members chevron | FAIL, fixed | pointed right when open; after: fix-390-group-info-open.png | e5bbfe0 |
| looks: mesh map labels, line of three | FAIL, fixed | link line struck through the names (390l-net.png); after: fix-390-map-A.png, fix-390-map-B.png | 6cbe065 |
| looks: "You left this group" for a removed member | FAIL, fixed | no leave action exists (20-C-removed-group-390.png); now "You are no longer in this group" | 0c4ef35 |
| looks: mesh map with 4 or 5 direct neighbours | FAIL | names overlap each other (fix-390-map-fake-4.png, fix-390-map-fake-5.png; page state faked, 3 real nodes only) | open |
| tests: ./gradlew :uiserver:test | PASS | 21 tests, 0 failures |  |
| tests: node meshcore.test.mjs and webnode-interop.mjs (SKIP_BUILD=1) | PASS | exit 0, ALL PASS |  |
| tests: scripts/e2e.sh 8281 8282 8283 on fresh nodes (final build) | PASS | items 1-7 PASS, exit 0 |  |

## Open (not attempted: larger than a page fix, or by design)

- **Verified and Muted do not survive a node restart**: core keeps them in memory only (MeshNode.verified is a HashSet; the data dir holds identity, messages, name). Seen on desktop: B verified Alice, after restart Alice is unverified. Not a page bug. **Fixed in core (8c1bb40):** saved in `marks.txt`, loaded at start, deleted by panic wipe.
- ~~**Mesh map crowds with 4+ direct neighbours**~~ Closed on `ui-boosters`: two rings with names outside, cut to fit, "+N" beyond 12 per ring; checked with 4, 6, 9, 12 and 14 direct neighbours at 390 px, and 6 and 12 also in dark mode and at 1280 px.
- **After a panic wipe the phone lists its own old ID as a contact in range**: on C: "Carol · e82a, 2 hops via Bob", not flagged revoked there (it wiped that knowledge). Core. The public feed also syncs back in (11 posts), which is expected for public posts. **Fixed in core (8c1bb40):** the wiped node ignores its own old ID.
- ~~**A removed member sees the group as "Group"**~~ Closed on `ui-boosters`: the page keeps the last known name (localStorage), also after a reload. A browser that never saw the name still shows "Group".
- **Browser Back after a reload or a tab switch needs extra presses**: old history entries stay behind and do nothing. Desktop browsers only; Android uses omBack() and is fine.
- ~~**Alert bar shows raw codes**~~ Closed on `ui-boosters` for the bar: a sentence with the name ("Someone is using Alice's name: ecbf", "Forged message blocked, via Bob · 7361"). The log keeps the code and detail on purpose; LINK_FLOOD, LINK_CLOSED, STALE, MALFORMED, BAD_POW and PEER_REVOKED still show as code plus detail in the bar.
- ~~**Guest with a wrong PIN sees the welcome dialog before the PIN prompt**~~ Closed on `ui-boosters`: the welcome screen waits for the link, so the PIN prompt comes first.
- ~~**Replay has no log line of its own**~~ Closed on `ui-boosters` with a local note under the attack buttons ("Replay sent: receivers drop it as a duplicate, see Duplicates dropped"); core still logs nothing.
- **Small polish**: Chats search matches the title and the last message only. Closed on `ui-boosters`: the QR indent in light mode, raw statistics names, and "1 link" in the header while "Reconnecting…".

## Not tested

- Rerouted DM ("rerouted via"): a line of three has no second path.
- "Queued" status: a DM to a stopped node showed "Sent", then "Delivered" after it came back.
- The real Android bridge, soft keyboard, safe-area insets and system Back: only a stubbed `OMNative` and `omBack()` in a browser. No emulators or phones were used.
- Real APK download: node A served a placeholder file, so only the link and the HTTP 200 were checked.
- Touch gestures and screen readers.
