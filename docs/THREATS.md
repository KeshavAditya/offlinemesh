# OfflineMesh threat model

What the system defends against, how, and what it does not defend against.
Plan references (5.x, section 12) point to Part 5 of `OFFLINEMESH_COMPLETE_GUIDE.md`.

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

Proof of work is a small extra cost per message. It is not the main defence.

## Known limitations

We say these out loud. They are not hidden.

### From the plan (section 12)

- **Many fake identities (Sybil).** Not prevented. All limits are per identity. One attacker can make many keys.
- **Proof of work is weak.** A post needs 15 bits (about 33,000 hashes). On our laptop one core does about 4.7 million hashes per second, so about 165 posts per second per core (see `NUMBERS.md`). It slows an attacker; it does not stop one.
- **Backdating.** A sender can claim any epoch in the last hour. So the real ceiling is 3 posts per epoch across 360 epochs, each still paying proof of work.
- **Clocks.** Rate limiting assumes phone clocks agree within about 20 seconds.
- **Metadata.** Relays see who talks to whom (source and destination IDs), message sizes and timing.
- **No forward secrecy.** A stolen private key decrypts all past DMs to and from that key.
- **One key** is used for both signing and key agreement.
- **Black-hole relay.** If the only path runs through a node that drops traffic, messages do not arrive. Sync only helps when another path exists.
- **TTL reset.** A malicious relay can reset TTL to its starting value. Duplicate suppression still stops storms.
- **Desk demo uses software muting** to force two hops. On a desk all phones are in range of each other.
- **Android only.** No iOS app. An iPhone can join only as a browser guest.

### Found while building

1. **The local UI API is not authenticated.** The UI server listens on 127.0.0.1:8080 with no password. This is on purpose, so `adb forward` and `scripts/e2e.sh` can drive a phone. Any other app on the same phone can send messages as this node. Web pages in a browser are blocked: the server checks that `Host` is loopback and that `Origin`, if sent, is a loopback page.
2. **Routes trust the hop count.** The hop count is not signed (5.7). A malicious relay can claim a short path, attract DMs, and drop them. It still cannot read or forge them. The sender sees no "Delivered" status.
3. **The link handshake can be relayed.** A man in the middle M can pass `HELLO_AUTH` between A and B, so A's link looks bound to B but runs through M. M gains only what any relay has: it can drop or delay. Envelope signatures and DM encryption still hold.
4. **Guests have no keys.** Guest posts (iPhone, judge's phone) are signed by the host phone as its own posts, shown as "guest of B". Guests are protected by a 4-digit PIN with per-IP and global wrong-PIN limits; the PIN rotates after too many wrong tries, and guest mode turns off after 3 rotations. Plain HTTP on the hotspot: anyone on the hotspot can read the feed traffic.
5. **Storage is simple.** Contact and route tables are capped and old entries age out. `messages.log` is append-only and never compacted. After a restart, your own sent DMs show "(sent before restart)" because contacts' public keys are not saved to disk, and decrypting your own DM needs the recipient's key. Received DMs carry the sender's key, so they and posts survive a restart intact.
6. **No QR scanning in the app.** The app shows a QR code of your ID, but cannot scan one. People verify each other by comparing the fingerprint text.
7. **Large meshes trip the link rate limit.** In the simulator, 50 honest nodes started at once log `LINK_FLOOD` and close 2 honest links, so 3 of 49 nodes miss a post. Cause: the burst of PRESENCE messages at start-up and at link-up (up to 64 cached presences) goes through the 30-per-10 s link bucket. Fine for a room of phones; reported to mesh-core (`core/src/test/kotlin/mesh/redteam/BroadcastCostTest.kt`, ignored test).
8. **Not yet tested on the real phones.** All six acceptance checks pass on three desktop nodes and on three Android emulators over emulated Bluetooth. Real-phone range, timing and OEM battery killing (OPPO, Samsung) are untested.

## Out of scope

- Physical attacks on a phone, or malware with root on it.
- Jamming the radio.
- Traffic analysis beyond what is listed under Metadata.
