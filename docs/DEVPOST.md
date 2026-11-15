# OfflineMesh (Devpost draft)

**Tagline:** A secure messaging protocol for phones with no internet and no server, where every relay is a stranger's phone. It protects privacy, detects attacks and mitigates them, with working code you can attack live.

**Not audited. Do not rely on it where your safety depends on it.** We built it in a weekend; nobody outside the team has reviewed it.

*Optional, only if the main theme is confirmed as "Let's Dig In":* the TUNNEL profile and the relay chain A → B → C are the literal reading, a message passed hand to hand out of a collapsed tunnel (Devpost title "SubTerra"; the code keeps its name).

## Inspiration

In a blackout, a flood or a packed stadium, the network is the first thing to go. Access Now counted 313 internet shutdowns in 52 countries in 2025, a record ([Access Now](https://www.accessnow.org/internet-shutdowns-2025/)). Phones still have Bluetooth. But a message that hops phone to phone passes through strangers, any of whom may read, change, drop or flood it.

The apps people reached for have broken in public. Bridgefy could be taken down network-wide by one crafted message, and even after it adopted the Signal protocol, researchers read 56 of 100 encrypted messages by re-running a handshake under the victim's ID ([Breaking Bridgefy](https://martinralbrecht.wordpress.com/wp-content/uploads/2020/08/bridgefy-abridged.pdf), [Breaking Bridgefy, again](https://www.usenix.org/system/files/sec22-albrecht.pdf)). Meshtastic vendors shipped one keypair on many devices ([CVE-2025-52464](https://github.com/meshtastic/firmware/security/advisories/GHSA-gq7v-jr8c-mfr7)). We built the protocol so that hostile relays do not matter, and wrote a test for each of those attacks.

## What it does (Privacy and Security Challenge)

**Protects user privacy**
- **Direct messages are encrypted end to end** (ECDH + AES-256-GCM). The phones that relay them cannot read them.
- **Length padding:** every DM is padded to a size bucket, so relays see sender, recipient, timing and a size bucket, never content or exact length.
- **Panic wipe:** type `WIPE` and the phone sends a signed revoke to its contacts, deletes its identity and messages, and starts over with a new ID. Contacts delete their conversation with the old key. It is a request, not a guarantee; the limits are in `docs/THREATS.md`.
- **No tracking handle in what a phone broadcasts:** the Bluetooth advert and the LAN discovery beacon carry a random tag that changes every session, not the node ID.
- **Group chats** of up to 16 people. A group message goes out as one encrypted DM to each member, so relays still see only DMs.

**Detects threats**
- **Every message is signed.** A relay that changes one bit is caught at the next phone, and the link that delivered it is blamed, not the named sender.
- **Security log** of each rejection as it happens: bad signature, bad proof of work, replay, link flood, rate-limit cheating.
- **Impersonation warning:** names can be copied, keys cannot. A newcomer using the name of a contact you verified is flagged "same name as a verified contact", even with look-alike letters, invisible characters or different spacing. It warns; it does not ban.
- **Dropped DMs are noticed:** no signed receipt in time means the route failed, and it is logged.

**Mitigates attacks**
- **Rate limit with proof:** 3 posts per 10 seconds per key, by numbered slot. Breaking it forces two signatures for one slot. That is proof anyone can check, and the key is banned.
- **Link scoring:** each link has a token bucket and a score; a misbehaving link is closed. A second handshake on a live link closes it.
- **DM failover:** a DM with no receipt is resent on a backup route through a different first link.
- **Built-in red-team buttons** (tamper, drop, flood, spoof, replay) so you can attack the mesh live and watch it hold.

**Also:** missed messages catch up when a phone comes back in range; live statistics counted by the code (drops by kind, duplicates, delivery time); mission profiles that trade airtime against delivery speed; photo DMs; a QR hop that carries a short signed message between two phones with every radio off; notifications with inline reply.

**Browser guests.** An iPhone, or any phone without the app, joins through the host phone's hotspot and runs a full mesh client in the page: its own key, signed posts, encrypted DMs and receipts. It is a leaf, not a relay. It trusts the host for its code, and we say so.

**Offline app sharing.** The host can hand the 2.4 MB Android app to a new phone without internet, through the share sheet or a download from the guest page, with a SHA-256 fingerprint to compare.

## "Does it just spam messages to everyone?"

No. Direct messages are routed: each phone learns from signed beacons which neighbour leads to each person, so a DM takes one path, one transmission per hop, and the receipt comes back the same way. In our simulator with 30 phones in a room, a delivered DM cost 5.17 transmissions against 97.80 for plain flooding. Public posts have to reach everyone, so each phone forwards a post once per link and never back to a neighbour that has it: 66.2 sends per post against 88.5 for plain flooding. We also built a self-repairing broadcast tree (Plumtree) that cuts posts to 36.2 sends, but in the same room it delivered 0.898 of posts against 0.993, so it is off by default. If a relay starts dropping messages, the sender notices the missing receipt and reroutes. All figures come from `docs/NUMBERS.md`, generated by `./gradlew :core:bench`.

## How we built it

- **Protocol core** in Kotlin, shared by the Android app and a desktop node. Identity is an EC key pair; the ID is a hash of the public key. ECDSA signatures, ECDH plus AES-256-GCM for DMs, a small proof of work per message, a numbered slot system for the post rate limit, and an ingress pipeline that runs cheap checks before expensive ones.
- **Routing:** broadcasts go once per link and never back to a neighbour that has them. DMs follow learned routes, keep a backup route, and receipts walk the same path back.
- **Android:** a foreground service runs Bluetooth LE L2CAP channels (connection-oriented, up to 60 KB frames) and a WebView UI. R8 shrinks the release APK from 12.8 MB to 2.4 MB.
- **UI:** plain HTML with inline CSS and JavaScript, no frameworks, no build step. Light and dark themes follow the phone.
- **Web client:** the protocol rewritten in JavaScript with the noble crypto library vendored in, because a plain-HTTP page gets no WebCrypto. Test vectors are checked byte for byte against the Kotlin core in both directions, and a browser node passes 11 of 11 interop checks against desktop nodes.
- **Desktop node** over TCP, used as the fallback demo and for testing.
- **Simulator:** an in-memory network with virtual time runs the real core code.
- **Tests:** 146 automated tests. 14 of them reproduce published attacks on other mesh apps: fuzzed and length-lying frames, relaying before checking, re-running the handshake on a live link, re-encoded signatures, cloned keys and restarts, failed decryption as an oracle, look-alike names, a stored key being overwritten, and path tricks against the guest download server. Two of them found gaps that we fixed: a second handshake on a live link, and look-alike names.
- **Benchmark:** `./gradlew :core:bench` compares plain flooding, our routing and the tree on four topologies and writes the table into `docs/NUMBERS.md`, so every figure we quote traces to code.
- One acceptance script (`scripts/e2e.sh`) drives three or more nodes over HTTP and checks eight goals: two-hop DM, relay cannot read it, post arrives once, tampering rejected, flood banned, catch-up after reconnect, group message delivered to every member, post reaches every node.

## Challenges we ran into

- **Punishing the right party.** Anyone can put someone else's public key on a forged message. So a bad signature penalises the link that delivered it, never the named sender. A key is banned only on cryptographic proof: two signed posts for the same slot.
- **Restarts.** A phone that restarts could reuse a post slot and get itself banned. It now waits for the next 10-second epoch before posting.
- **Sync amplification.** Our own red-team tests found that one request could pull 512 copies of a message. Fixed in core before the demo.
- **Handshake replay.** Our Bridgefy-style test showed a second handshake on a live link was not closing it. Fixed.
- **Browsers on a plain-HTTP hotspot** get no WebCrypto and no trusted certificate offline. We bundled pure-JS crypto seeded from the one random source browsers still allow.
- **Phones sleep.** On the real-phone run the OPPO's battery saving stopped the app mid-test. We added a battery-exemption prompt and a per-brand "keep alive" checklist.
- **The tree was not a free win.** Plumtree roughly halved the sends per post, but tree paths are longer than shortest paths, so the 6-hop limit cuts off far phones. We measured it, kept flooding as the default, and say so.

## Accomplishments we're proud of

- All eight acceptance checks pass on desktop nodes (3, 4 and 5) and on four Android emulators talking over emulated Bluetooth, with the shrunk release APK and a browser guest.
- On two real phones and a laptop node, six of seven checks passed; one Bluetooth DM took 560 ms.
- One public post on a 25-phone simulated mesh with 40 links took 40 to 42 transmissions, about one per link.
- Every efficiency figure we quote comes from a generated benchmark table, including the ones that do not flatter us.
- An honest list of what we do not defend against (`docs/THREATS.md`).

## What we learned

- Proof of work is a weak defence: a laptop mines about 110 to 165 posts per second per core at our difficulty. Rate limits with signed slots do the real work.
- Bluetooth links are point-to-point, so tricks that rely on a shared broadcast radio save nothing here.
- Measure before you claim: the routing idea that sounded best (the tree) lost delivery in our own benchmark.

## Honest limits

- **Not audited.**
- Tested in the simulator, on desktop nodes and on emulators. Only one real-phone run so far, and one of its checks failed because the phone slept. Bluetooth range and phone proof-of-work time are not measured.
- No protection against one person making many identities; each one gets its own rate limit.
- Metadata is visible to relays and to radio listeners: who talks to whom, timing and a size bucket. Any connected peer sees which recent message IDs a phone carries.
- No forward secrecy, and DMs are signed: a seized phone's key decrypts its past DMs and proves who wrote them. Panic wipe helps only if pressed in time, and it is a request: a modified or offline phone keeps its copy.
- One key does both signing and encryption. Keys are software keys in app storage, not in the phone's hardware keystore.
- A browser guest trusts the host phone for its code, over plain HTTP, and keeps its key in browser storage.
- A first install shared over the hotspot is only as safe as the hotspot password and an independent fingerprint check.
- Failover only guarantees a different first hop.
- A 2.4 GHz jammer stops Bluetooth. We need no cell tower or GPS, and the fallbacks are the LAN transport on a 5 GHz hotspot and the QR hop.
- Android only; iPhones join as browser guests.
- On a desk all phones are in range, so the demo forces two hops in software.

## What's next

- A full test on the three real phones, with range and timing.
- Forward secrecy without a server: rotating signed encryption keys gossiped in presence, which also separates the signing and encryption keys.
- Keys in Android Keystore or StrongBox.
- Long-range Bluetooth mode where the phone supports it; not range-tested.

## Built with

Kotlin, Android (Bluetooth LE L2CAP, foreground service, WebView, R8), Ktor, kotlinx.serialization, ZXing, Java security (ECDSA, ECDH, AES-GCM, SHA-256), JavaScript with noble (curves, hashes, ciphers), Gradle, Node test runner, bash, curl, jq.
