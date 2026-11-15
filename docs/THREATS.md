# OfflineMesh threat model

What the system defends against, how, and what it does not defend against.
Plan references (5.x, 13.2, section 12) point to `OFFLINEMESH_COMPLETE_GUIDE.md`. U1 to U11 point to `OFFLINEMESH_UPDATE_PLAN_V2.md`.

The v2 items (U2, U3, U6 to U10) are built on the `v2` branch and tested in the simulator, on desktop nodes and on emulators. They are not on the real phones yet.

## Setting

- Phones talk over Bluetooth LE (L2CAP links) or TCP on a LAN. No internet, no server.
- Anyone can join. There is no admin and no membership list.
- Relays are other people's phones. We assume some of them are hostile.

## What an attacker can do, and what stops it

| Attack | Defence | Where |
|---|---|---|
| Read a DM it relays | DMs are AES-256-GCM encrypted with a key only the two endpoints can derive (ECDH). Relays see ciphertext only. | 5.4 |
| Change a message it relays | Every envelope is signed (ECDSA) and its ID is a hash of its content. The next honest node rejects it and logs `BAD_SIGNATURE`. | 5.3, 5.6 step 6 |
| Pretend to be someone else | A node ID is a 64-bit hash of its public key. Every message is signed by that key. You cannot sign without the private key. | 5.1 |
| Forge a message with someone else's key | Signature fails. The link that delivered it loses 100 points and is closed. The named source is not punished. | 5.6 rule |
| Flood public posts | 3 posts per 10 s epoch per key, by numbered slot. Two different signed posts for one slot are proof of cheating; the key is banned for 10 minutes. The two posts are gossiped as a `BAN_PROOF` so nodes that did not see both also ban. | 5.6 step 8 |
| Flood a link with junk | Each link has a token bucket (30 envelopes per 10 s) and a score. Malformed −20, bad proof of work −50, bad signature −100, over rate −10. Below 0 the link is closed and that peer is refused for 60 s. | 5.6 |
| Make one message reach everyone forever | Broadcasts are sent once per link, never back to a neighbour that has them, and stop after 6 hops. Cost is about one transmission per link. | 5.7 |
| Replay old messages | Seen set (4,096 IDs) drops copies. Messages older than one hour are rejected as `STALE`. | 5.6 steps 2, 7 |
| Forge delivery receipts (ACK) | An ACK is accepted only for a real DM in the seen set, only from that DM's recipient, and only once. | 5.6 step 8 |
| Spam presence beacons | One PRESENCE per key per 10 s; older ones are ignored. | 5.6 step 8 |
| Connect and say nothing | No valid `HELLO_AUTH` within 5 s closes the link. A link silent for 45 s is closed. | 5.5 |
| Use the SYNC protocol to amplify | Duplicate IDs in one `SYNC_WANT` are answered once. Envelopes the link already has are not resent. Outstanding wants are capped. | 5.8 |
| Read a group message it relays | A group message is sent as one ordinary DM to each other member. Every copy is end-to-end encrypted to that member. Relays see only DMs. | 13.2 |
| Claim or take over someone else's group | The group id is bound to the owner: gid = first 8 bytes of H(ownerId \|\| salt). An invite is dropped unless its gid matches its sender. Only the owner can change membership. | 13.2 |
| Hide a member's group message by reusing its id | De-duplication is per sender: (group id, sender id, message id). Two senders with the same message id both show. | 13.2 |
| Learn what a DM says from its length | Every DM plaintext is padded to a size bucket: 64, 128, 256, 512, 1024, 2048, 4096 bytes, then steps of 4096. A 1-letter and a 50-letter DM look the same size. A revoke looks like a short text. | U7 |
| Copy the name of someone I trust | If a newcomer's name matches (ignoring case and spaces) a contact I marked verified, it is marked suspect, `NAME_IMPERSONATION` is logged, and the UI shows "same name as a verified contact". Unverified names always show the first 4 hex digits of the ID (`Sam · 3fa9`). | U8 |
| Relay that drops DMs (black hole) | The sender keeps a backup route through a different first link. No ACK within 3 s + 1 s per hop: the same envelope is resent on the backup (or flooded once if there is none), at most twice, and `ROUTE_FAILED` is logged. | U10 |
| My phone is about to be taken | Panic wipe (type `WIPE`): a revoke DM (kind 4) goes to every DM contact and group member, then the node deletes its identity, message log and contacts and starts with a new ID. A peer that gets the revoke deletes its DMs with that key and its group messages, drops groups that key owns, and logs `PEER_REVOKED`. | U2 |
| Carry a message past a jammed radio | A short envelope (500 bytes or less) can be exported and imported as a QR code. The import runs the full ingress pipeline, so signature and proof of work are still checked. Core and API only so far; the Android QR screens are coming. | U6 |
| Fill my group table with invites | At most 4 groups per unverified owner. When the table of 32 is full, the oldest group I never posted in and whose owner is unverified is evicted; if there is no such group, the invite is dropped. Verified owners have a hard cap of 64. | 13.2 |
| Send a group message without an invite | Dropped. A group message from a non-member, or one that arrives before its invite, is not shown. | 13.2 |

Proof of work is a small extra cost per message. It is not the main defence.

## Known limitations

We say these out loud. They are not hidden.

### From the plan (section 12)

- **Many fake identities (Sybil).** Not prevented. All limits are per identity. One attacker can make many keys.
- **Proof of work is weak.** A post needs 15 bits (about 33,000 hashes). On our laptop one core does about 4.7 million hashes per second, so about 165 posts per second per core (see `NUMBERS.md`). It slows an attacker; it does not stop one.
- **Backdating.** A sender can claim any epoch in the last hour. So the real ceiling is 3 posts per epoch across 360 epochs, each still paying proof of work.
- **Clocks.** Rate limiting assumes phone clocks agree within about 20 seconds.
- **Metadata.** Relays and radio listeners see who talks to whom (source and destination IDs), timing and a size bucket (U7), never content or exact length.
- **No forward secrecy.** A stolen private key decrypts all past DMs to and from that key.
- **One key** is used for both signing and key agreement.
- **Black-hole relay.** If the only path runs through a node that drops traffic, messages do not arrive. U10 failover and sync only help when another path exists.
- **TTL reset.** A malicious relay can reset TTL to its starting value. Duplicate suppression still stops storms.
- **Desk demo uses software muting** to force two hops. On a desk all phones are in range of each other.
- **Android only.** No iOS app. An iPhone can join only as a browser guest.

### v2 additions (U2 to U10)

**Panic wipe (U2)**
- It is a request. A modified or offline recipient keeps its copy.
- Deleted files on flash storage may be recoverable with forensic tools.
- Relay copies are ciphertext and expire within the hour; they are not recalled.
- It protects only if the button is pressed before the phone is taken.
- A revoked peer's public posts stay on other phones. Only DMs, group messages and owned groups are removed.

**Length padding (U7)**
- Relays still see a size bucket. Two short texts look the same, but a short text and a photo can still be told apart.

**Impersonation warning (U8)**
- It only warns. Nothing is banned, because sharing a name is not proof of malice.
- It only protects names of contacts you marked verified.

**DM failover (U10)**
- Only the first hop is guaranteed to differ. A black hole that sits on both paths further on is not avoided.
- Nothing is banned. A dropped link and a malicious relay look the same, so the response is only a routing preference.

**Tree broadcast (U9)**
- `TREE` is not on by default. Nodes ship with `BROADCAST_MODE = FLOOD`. In the benchmark TREE cuts sends per post but loses delivery (see `NUMBERS.md`).
- Hop counts in IHAVE notices are not signed. A neighbour can lie about its distance and make itself a node's tree parent.
- If that parent then withholds posts, the old parent still sends IHAVE notices, and a GRAFT after 1.5 s pulls the post from it. Withholding only delays.

**QR hop (U6)**
- A DM carried by QR gets no ACK back, so the sender does not see "Delivered".

**Mission profiles (U3)**
- Profiles change only local timers and limits. They add no security.

### Group chats (section 13.2)

- **A group message costs one DM, with its own proof of work, per other member.** Groups are capped at 16 members, so at most 15 DMs per message.
- **Relays can see the fan-out.** A relay can see that one source sent DMs to several ids at the same time, and guess they are a group.
- **Removal is not erasure.** Only the owner can change membership. A removed member gets no new messages, but keeps the history it already has.
- **Fake identities can still churn groups.** Many cheap keys (Sybil), 4 groups each, can keep evicting the groups I never posted in. Groups I posted in, and groups owned by verified contacts, are not evicted.
- **Order matters.** A group message that arrives before its invite is dropped.

### Found while building

1. **The local UI API is not authenticated.** The UI server listens on 127.0.0.1:8080 with no password. This is on purpose, so `adb forward` and `scripts/e2e.sh` can drive a phone. Any other app on the same phone can send messages as this node. Web pages in a browser are blocked: the server checks that `Host` is loopback and that `Origin`, if sent, is a loopback page.
2. **Routes trust the hop count.** The hop count is not signed (5.7). A malicious relay can claim a short path, attract DMs, and drop them. It still cannot read or forge them. The sender sees no "Delivered" status, and U10 resends on a backup route.
3. **The link handshake can be relayed.** A man in the middle M can pass `HELLO_AUTH` between A and B, so A's link looks bound to B but runs through M. M gains only what any relay has: it can drop or delay. Envelope signatures and DM encryption still hold.
4. **Guests have no keys.** Guest posts (iPhone, judge's phone) are signed by the host phone as its own posts, shown as "guest of B". Guests are protected by a 4-digit PIN with per-IP and global wrong-PIN limits; the PIN rotates after too many wrong tries, and guest mode turns off after 3 rotations. Plain HTTP on the hotspot: anyone on the hotspot can read the feed traffic. This is the read-only page, now at `/lite`; `/` serves the full browser client (item 9).
5. **Storage is simple.** Contact and route tables are capped and old entries age out. `messages.log` is append-only and never compacted. After a restart, your own sent DMs show "(sent before restart)" because contacts' public keys are not saved to disk, and decrypting your own DM needs the recipient's key. Received DMs carry the sender's key, so they and posts survive a restart intact.
6. **No QR scanning in the app yet.** The app shows a QR code of your ID, but cannot scan one. People verify each other by comparing the fingerprint text. U6 QR import exists in core and the API; the Android scan screen is coming.
7. **One neighbour can crowd a link's send budget.** Each node paces its own sending to 25 envelopes per 10 s per link, so honest nodes never trip the receiver's 30-per-10 s limit (50 honest simulated nodes: no security events, every node gets the post). But the outgoing queue is shared, not fair per source: a neighbour that relays many valid, PoW-paid envelopes can use most of another link's budget and delay honest relays. Extra relayed PRESENCE is dropped first; own posts and DMs stay in the store and still reach peers through sync.
8. **Not yet tested on the real phones.** All six acceptance checks pass on three desktop nodes and on three Android emulators over emulated Bluetooth. Real-phone range, timing and OEM battery killing (OPPO, Samsung) are untested. Long-range Bluetooth (U5) is guarded code only, waiting for real phones.
9. **The browser client trusts the host that serves it.** A browser guest at `http://host:8090/?pin=` runs the full web client (`webbackend.js`, `meshnode.js`, `meshcore.js`, `noble.js`) and becomes a leaf mesh node with its own key. The host serves that JavaScript over plain HTTP, so a dishonest or compromised host (or anyone who can tamper with hotspot traffic) can serve modified JS that leaks the guest's private key and messages. HTTPS is not used, by decision: there is no certificate a phone on an offline hotspot would trust. Mitigations: join only a host you trust; people at high risk install the Android app instead (the page links `/app.apk` and shows the file's SHA-256 so it can be checked against a known-good copy before installing); the app path does not depend on the host's JS. The browser's key, contacts and last 200 messages live in `localStorage` in plain text: anyone with the unlocked phone, or script on the same origin, can read them. In private browsing (no storage) the guest gets a new identity on every reload.

## Out of scope

- Physical attacks on a phone, or malware with root on it.
- Jamming the radio. A 2.4 GHz jammer stops Bluetooth. The fallbacks are the LAN transport on a 5 GHz hotspot, and the QR hop (U6) once its screens exist.
- Traffic analysis beyond what is listed under Metadata.
