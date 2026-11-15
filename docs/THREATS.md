# OfflineMesh threat model

**Not audited. Do not rely on it where your safety depends on it.** This is a hackathon build. Nobody outside the team has reviewed the code or the protocol.

What the system defends against, how, and what it does not defend against.
Plan references (5.x, 13.2, section 12) point to `OFFLINEMESH_COMPLETE_GUIDE.md`. U1 to U11 point to `OFFLINEMESH_UPDATE_PLAN_V2.md`. T1 to T11 are the red-team tests in `core/src/test/kotlin/mesh/redteam/RedTeamTest.kt` (and `uiserver/src/test/kotlin/mesh/ui/GuestServerAttackTest.kt` for T8), each based on a published attack on another mesh app.

Where things were tested is in "How far this is tested" below. In short: the simulator, desktop nodes and Android emulators run everything; one real-phone run covered the acceptance checks.

## Setting

- Phones talk over Bluetooth LE (L2CAP links) or TCP on a LAN. No internet, no server.
- Anyone can join. There is no admin and no membership list.
- Relays are other people's phones. We assume some of them are hostile.
- A browser (for example an iPhone) can join through a host phone's hotspot as a leaf node with its own key. It cannot relay and does not use Bluetooth.

## What an attacker can do, and what stops it

| Attack | Defence | Where |
|---|---|---|
| Read a DM it relays | DMs are AES-256-GCM encrypted with a key only the two endpoints can derive (ECDH). Relays see ciphertext only. | 5.4 |
| Change a message it relays | Every envelope is signed (ECDSA) and its ID is a hash of its content. The next honest node rejects it and logs `BAD_SIGNATURE`. Bad envelopes stop at the first honest node and are never forwarded (T2). | 5.3, 5.6 step 6 |
| Pretend to be someone else | A node ID is a 64-bit hash of its public key. Every message is signed by that key. You cannot sign without the private key. A contact's key never changes for its ID, whatever later packets say (T11). | 5.1 |
| Copy the display name of someone I trust | If a newcomer's name matches a contact I marked verified, it is marked suspect, `NAME_IMPERSONATION` is logged, and the UI shows "same name as a verified contact". The match ignores case, all spaces, invisible and direction characters (such as U+200B, U+202E), full-width forms (NFKC) and common look-alikes (Cyrillic and Greek letters, 0/O, 1/l). Unverified names always show the first 4 hex digits of the ID (`Sam · 3fa9`). Names are capped at 32 bytes and hostile names decode safely (T7). | U8 |
| Forge a message with someone else's key | Signature fails. The link that delivered it loses 100 points and is closed. The named source is not punished. | 5.6 rule |
| Re-run the handshake on a live link to take it over | A second `HELLO` or `HELLO_AUTH` on a link that is already bound closes it. A `HELLO_AUTH` replayed from an old session fails, because it signs a fresh nonce (T3). DM keys come from the recipient's key, never from what the link claims. | 5.5 |
| Crash a node with a broken or huge frame | Frames over 60,000 bytes are refused. Nothing is decompressed. Fuzzed, truncated and length-lying frames decode to "malformed" without an exception, and the link is closed (T1). | 5.2, 5.6 |
| Make a new ID for the same message by re-encoding it | The message ID is a hash of the signed content without the signature, so signature variants keep the same ID and cannot be used to build a ban proof against an honest key (T4). | 5.3 |
| Flood public posts | 3 posts per 10 s epoch per key, by numbered slot. Two different signed posts for one slot are proof of cheating; the key is banned for 10 minutes. The two posts are gossiped as a `BAN_PROOF` so nodes that did not see both also ban. A restart does not reuse a slot (T5). | 5.6 step 8 |
| Flood a link with junk | Each link has a token bucket (30 envelopes per 10 s) and a score. Malformed −20, bad proof of work −50, bad signature −100, over rate −10. Below 0 the link is closed and that peer is refused for 60 s. | 5.6 |
| Make one message reach everyone forever | Broadcasts are sent once per link, never back to a neighbour that has them, and stop after 6 hops. Cost is about one transmission per link. | 5.7 |
| Replay old messages | Messages older than one hour are rejected as `STALE`. A recent copy is dropped by the seen set (4,096 most recently used IDs). The ID of every accepted post and DM is also kept for the full hour, so pushing it out of the seen set with other traffic does not let a replay through (tested). | 5.6 steps 2, 7 |
| Forge delivery receipts (ACK) | An ACK is accepted only for a real DM in the seen set, only from that DM's recipient, and only once. | 5.6 step 8 |
| Learn something from a DM that fails to decrypt | A DM that fails GCM is dropped. It is not shown, not acknowledged, and sends no error back (T6). | 5.4 |
| Spam presence beacons | One PRESENCE per key per 10 s; older ones are ignored. | 5.6 step 8 |
| Connect and say nothing | No valid `HELLO_AUTH` within 5 s closes the link. A link silent for 45 s is closed. | 5.5 |
| Use the SYNC protocol to amplify | Duplicate IDs in one `SYNC_WANT` are answered once. Envelopes the link already has are not resent. Outstanding wants are capped. | 5.8 |
| Read a group message it relays | A group message is sent as one ordinary DM to each other member. Every copy is end-to-end encrypted to that member. Relays see only DMs. | 13.2 |
| Claim or take over someone else's group | The group id is bound to the owner: gid = first 8 bytes of H(ownerId \|\| salt). An invite is dropped unless its gid matches its sender. Only the owner can change membership. | 13.2 |
| Hide a member's group message by reusing its id | De-duplication is per sender: (group id, sender id, message id). Two senders with the same message id both show. | 13.2 |
| Fill my group table with invites | At most 4 groups per unverified owner. When the table of 32 is full, the oldest group I never posted in and whose owner is unverified is evicted; if there is no such group, the invite is dropped. Verified owners have a hard cap of 64. | 13.2 |
| Send a group message without an invite | Dropped. A group message from a non-member, or one that arrives before its invite, is not shown. | 13.2 |
| Learn what a DM says from its length | Every DM plaintext is padded to a size bucket: 64, 128, 256, 512, 1024, 2048, 4096 bytes, then steps of 4096. A 1-letter and a 50-letter DM look the same size. A revoke looks like a short text. | U7 |
| Relay that drops DMs (black hole) | The sender keeps a backup route through a different first link. No ACK within 3 s + 1 s per hop: the same envelope is resent on the backup (or flooded once if there is none), at most twice, and `ROUTE_FAILED` is logged. | U10 |
| My phone is about to be taken | Panic wipe (type `WIPE`): a revoke DM (kind 4) goes to every DM contact and group member, then the node deletes its identity, message log and contacts and starts with a new ID. A peer that gets the revoke deletes its DMs with that key and its group messages, drops groups that key owns, and logs `PEER_REVOKED`. The browser client has the same button. | U2 |
| Carry a message past a jammed radio | A short envelope (500 bytes or less) can be shown as a QR code on one phone and scanned on another (Android "QR hop" screen). The import runs the full ingress pipeline, so signature and proof of work are still checked. | U6 |
| Track a phone by what it broadcasts | The Bluetooth scan response and the LAN UDP discovery beacon each carry a 4-byte tag drawn at random each time that transport starts, not the node ID. Identity is proven only inside the link (`HELLO_AUTH`). See the limits below. | plan 8 |
| Read or plant files through the guest web server | The guest server answers only its fixed pages, scripts and the APK. Path traversal, odd paths, `POST` to the APK and `Range` headers never return another file, and pages never write mesh data as HTML (T8). | 5.13 |
| Exhaust the guest host by opening many sockets | The guest port front proxy caps spliced connections at 24 per guest IP and 96 in total (extras are closed at once), drops sockets that send nothing within 5 s, and reclaims spliced sockets idle for 120 s in either direction. | 5.13 |
| Hand someone a malicious copy of the app | On first run the app shows its APK file SHA-256 and signing-certificate SHA-256 once, to compare with the sharer's "Share app" dialog; the guest page shows the file's SHA-256 next to the download. See the limits below: the download is plain HTTP and a fake app can print any fingerprint. | offline sharing |

Proof of work is a small extra cost per message. It is not the main defence.

## Known limitations

We say these out loud. They are not hidden.

### Protocol and crypto

- **Many fake identities (Sybil).** Not prevented. All limits are per identity. One attacker can make many keys.
- **Proof of work is weak.** A post needs 15 bits (about 33,000 hashes). On our laptop one core mines a post in 6 to 9 ms, so about 110 to 165 posts per second per core (see `NUMBERS.md`). It slows an attacker; it does not stop one.
- **Backdating.** A sender can claim any epoch in the last hour. So the real ceiling is 3 posts per epoch across 360 epochs, each still paying proof of work.
- **Clocks.** Rate limiting assumes phone clocks agree within about 20 seconds.
- **64-bit message IDs.** Forging a message with someone else's ID costs about 2^64 hashes. A sender can make two of *its own* messages share an ID in about 2^32 tries (our arithmetic: about 15 minutes on one laptop core). It cannot do this to anyone else's messages.
- **One key** is used for both signing and key agreement. No known attack, but it goes against NIST key-usage rules (SP 800-56A), and it means the encryption key cannot be rotated without changing identity.
- **No forward secrecy.** A stolen private key decrypts all past DMs to and from that key. Meshtastic's DMs and bitchat's offline path have the same gap. Fixing it without a server is designed (rotating signed keys in presence), not built.
- **Random IVs.** Each DM uses a random 96-bit IV. NIST SP 800-38D caps that at 2^32 messages per key, which a phone never approaches.
- **Keys are software keys** in the app's private storage (`identity.bin`). Android Keystore or StrongBox would stop a rooted attacker from copying the key out (not from using it while in control of the phone).
- **TTL reset.** A malicious relay can reset TTL to its starting value. Duplicate suppression still stops storms.
- **Seen-ID memory is not capped.** Accepted post and DM IDs are kept for one hour. Many keys sending at full rate grow this table for that hour.

### Metadata and tracking

- **Metadata.** Relays and radio listeners see who talks to whom (source and destination IDs), timing and a size bucket (U7), never content or exact length.
- **Distance from timing.** A relay can time a DM against its ACK to estimate how many hops away the recipient is.
- **Sync shows what I carry.** Any peer that completes the handshake gets a `SYNC_OFFER` listing the IDs of up to 512 envelopes I hold from the last hour, and can ask for them. DMs stay encrypted, but the list shows what I have carried.
- **Bluetooth advert tag.** The random tag is new each time the Bluetooth transport starts (app start, and each restart of the transport, for example after a panic wipe), so sessions cannot be linked by it. Within one session it stays fixed, along with the channel number (PSM), even when the phone's Bluetooth address rotates. Anyone who connects learns the node ID in the handshake.
- **LAN beacon.** UDP discovery on a LAN also carries a random 4-byte tag per transport start, not the node ID. The same limits apply: fixed within a session, and anyone who connects learns the node ID.

### Seizure and devices

- **A seized phone proves authorship.** DMs are signed, so a captured DM shows who wrote it. With no forward secrecy, the phone's key also decrypts all its past DMs. Panic wipe is the only mitigation, and only if pressed in time.
- **A key copied to two devices gets banned.** Two devices with one key will both post into the same slots, which looks exactly like cheating, and the key is banned. A restart on one device does not trigger this (T5). Note: the Android app does not opt out of Android backup, and `identity.bin` sits in its files folder, so a backup restore or phone-to-phone transfer could copy the key to a second device (reported in `STATUS.md`).

### Panic wipe (U2)

- It is a request. A modified or offline recipient keeps its copy.
- Deleted files on flash storage may be recoverable with forensic tools.
- Relay copies are ciphertext and expire within the hour; they are not recalled.
- It protects only if the button is pressed before the phone is taken.
- A revoked peer's public posts stay on other phones. Only DMs, group messages and owned groups are removed.

### Length padding (U7)

- Relays still see a size bucket. Two short texts look the same, but a short text and a photo can still be told apart.

### Impersonation warning (U8)

- It only warns. Nothing is banned, because sharing a name is not proof of malice.
- It only protects names of contacts you marked verified.
- The look-alike list covers common cases, not every Unicode confusable.
- The browser client runs the same name check, ported to JavaScript.

### DM failover (U10)

- Only the first hop is guaranteed to differ. A black hole that sits on both paths further on is not avoided.
- Nothing is banned. A dropped link and a malicious relay look the same, so the response is only a routing preference.
- If the only path runs through a node that drops traffic, messages do not arrive. Failover and sync only help when another path exists.

### Tree broadcast (U9)

- `TREE` is not on by default. Nodes ship with `BROADCAST_MODE = FLOOD`. In the benchmark TREE cuts sends per post but loses delivery (see `NUMBERS.md`).
- Hop counts in IHAVE notices are not signed. A neighbour can lie about its distance and make itself a node's tree parent.
- If that parent then withholds posts, the old parent still sends IHAVE notices, and a GRAFT after 1.5 s pulls the post from it. Withholding only delays.

### QR hop (U6)

- A DM carried by QR gets no ACK back, so the sender does not see "Delivered".
- The QR screens were checked on emulators (QR drawn, scanner opens). A live scan between two real phones has not been tested.

### Mission profiles (U3)

- Profiles change only local timers and limits. They add no security.

### Group chats (section 13.2)

- **A group message costs one DM, with its own proof of work, per other member.** Groups are capped at 16 members, so at most 15 DMs per message.
- **Relays can see the fan-out.** A relay can see that one source sent DMs to several ids at the same time, and guess they are a group.
- **Removal is not erasure.** Only the owner can change membership. A removed member gets no new messages, but keeps the history it already has.
- **Fake identities can still churn groups.** Many cheap keys (Sybil), 4 groups each, can keep evicting the groups I never posted in. Groups I posted in, and groups owned by verified contacts, are not evicted.
- **Order matters.** A group message that arrives before its invite is dropped.

### Browser client

- **The browser trusts the host that serves it.** A browser guest at `http://host:8090/?pin=` runs the full web client (`webbackend.js`, `meshnode.js`, `meshcore.js`, `noble.js`) and becomes a leaf mesh node with its own key. It signs and decrypts its own traffic, so end-to-end protection holds against other nodes and relays. But the host serves that JavaScript over plain HTTP, so a dishonest or compromised host, or anyone who can tamper with hotspot traffic, can serve modified code that leaks the guest's key and messages. HTTPS is not used, by decision: there is no certificate a phone on an offline hotspot would trust, and shipping one private key in every app would count as a leaked key.
- **Mitigations:** join only a host you trust; people at high risk install the Android app instead (see "Offline app sharing"). The app does not depend on the host's code.
- **Storage.** The browser's key, contacts and last 200 messages live in `localStorage` in plain text. Anyone with the unlocked phone, or any script on the same origin, can read them. In private browsing (no storage) the guest gets a new identity on every reload. Safari may delete the storage after seven days without a visit, so treat a browser identity as short-lived.
- **Randomness.** Keys and IVs come from `crypto.getRandomValues` (through the bundled noble library), the one browser crypto call that works on a plain-HTTP page.
- **Read-only page.** `/lite` is the old keyless page: it reads the feed over plain HTTP, and its posts are signed by the host phone as "guest of B". The guest PIN (4 digits, per-IP and global wrong-PIN limits, rotates after too many wrong tries, guest mode off after 3 rotations) only keeps casual users out. It is not a secret against anyone on the hotspot.

### Offline app sharing

- **First install is only as safe as the hotspot and the fingerprint check.** The guest page offers `/app.apk` over plain HTTP. Anyone who can tamper with hotspot traffic can swap the file, and the SHA-256 shown next to it comes from the same server.
- **A fake app can print any fingerprint.** The sharer's "Share app" dialog shows the signing-certificate SHA-256, and the new app shows its file and certificate SHA-256 once on first run. Comparing them catches a corrupted or carelessly swapped file, not a careful attacker: a malicious app can show the genuine numbers. The strong check is an independent tool, for example `apksigner verify --print-certs` or `sha256sum` on a computer you trust.
- **Two different hashes.** The guest page shows the hash of the APK file. The "Share app" dialog shows the hash of the signing certificate. The first-run screen shows both. Compare like with like.
- **The app never installs apps itself.** It has no `REQUEST_INSTALL_PACKAGES` permission: the receiving phone's own installer (share sheet or browser download) does the install and shows Android's usual unknown-sources prompt.
- **Debug signing key.** The demo release APK is signed with the build laptop's debug key, not a release key.
- **Hotspot password.** "Invite guests" either starts Android's local-only hotspot (the system picks the name and password and the app shows them as QR 1) or uses the phone's own hotspot, whose password the host types in and gets a warning if it is under 16 characters. On the emulator the system-chosen password was 15 characters, below that guidance, and the app cannot change it. Briar's audit found an 8-character hotspot password crackable in about 15 days on one cloud GPU. The guest PIN is not the Wi-Fi password.
- **Developer verification.** From 2027 Android plans to require registered developers for sideloaded apps. Whether that check works offline at install time is not known.

### Built this way on purpose

1. **The local UI API is not authenticated.** The UI server listens on 127.0.0.1:8080 with no password. This is on purpose, so `adb forward` and `scripts/e2e.sh` can drive a phone. Any other app on the same phone can send messages as this node. Web pages in a browser are blocked (CSRF, cross-site WebSocket hijacking, DNS rebinding: fixed). Every route checks that `Host` is loopback (any port, for `adb forward`) and that `Origin`, if sent, is exactly `http://<Host>`, so a page on another loopback port, a `file://` page (`Origin: null`) or a rebound domain is refused; every POST also needs the header `X-OM: 1`, which a cross-site page cannot send without a CORS preflight the server never answers, so `no-cors` and form posts fail. Scripts that drive a node with curl must send `-H 'X-OM: 1'` (`scripts/e2e.sh` does).
2. **Routes trust the hop count.** The hop count is not signed (5.7). A malicious relay can claim a short path, attract DMs, and drop them. It still cannot read or forge them. The sender sees no "Delivered" status, and U10 resends on a backup route.
3. **The link handshake can be relayed.** A man in the middle M can pass `HELLO_AUTH` between A and B, so A's link looks bound to B but runs through M. M gains only what any relay has: it can drop or delay. Envelope signatures and DM encryption still hold. (This is different from T3: M cannot take over a link that is already bound.)
4. **Storage is simple.** Contact and route tables are capped and old entries age out. `messages.log` is append-only and never compacted. After a restart, your own sent DMs show "(sent before restart)" because contacts' public keys are not saved to disk, and decrypting your own DM needs the recipient's key. Received DMs carry the sender's key, so they and posts survive a restart intact.
5. **Verifying people.** The app shows a QR code of your ID and its fingerprint. People verify each other by comparing the fingerprint in person; the app's QR scanner is for the QR hop, not for identity.
6. **One neighbour can crowd a link's send budget.** Each node paces its own sending to 25 envelopes per 10 s per link, so honest nodes never trip the receiver's 30-per-10 s limit (50 honest simulated nodes: no security events, every node gets the post). But the outgoing queue is shared, not fair per source: a neighbour that relays many valid, PoW-paid envelopes can use most of another link's budget and delay honest relays. Extra relayed PRESENCE is dropped first; own posts and DMs stay in the store and still reach peers through sync.
7. **Desk demo uses software muting** to force two hops. On a desk all phones are in range of each other.
8. **Android only.** No iOS app. An iPhone can join only as a browser guest.

### How far this is tested

- **Simulator and unit tests:** `./gradlew test` runs 146 tests, all passing (core 121, of which 29 are red-team; uiserver 16, including the 4 guest-server attack tests; app 4; lan 3; desktop 2).
- **Desktop and emulators:** all eight acceptance checks (`scripts/e2e.sh`) pass on 3, 4 and 5 desktop nodes and on four Android emulators over emulated Bluetooth, including the R8 release APK and a browser guest.
- **Real phones, one run:** OPPO and A52s over Bluetooth plus a laptop node over LAN (no Fold 7) passed checks 1, 2, 3, 4, 6 and 7. Check 5 failed because the OPPO went silent mid-test: Android's battery saving or ColorOS stopped the app. So OEM battery killing is observed, not hypothetical. The app now asks to be exempt from battery optimisation on first run and has a "Keep alive" checklist per brand; that fix has not been re-run on the OPPO. Keep demo phones on chargers.
- **Not tested on real phones:** Bluetooth range, long-range Bluetooth (U5, guarded code only), the QR hop between two phones, and PoW time on each phone.

## Out of scope

- Physical attacks on a phone, or malware with root on it.
- Jamming the radio. A 2.4 GHz jammer stops Bluetooth. The fallbacks are the LAN transport on a 5 GHz hotspot, and the QR hop (U6).
- Traffic analysis beyond what is listed under Metadata.
