# OfflineMesh (Devpost draft)

**Tagline:** Secure messaging between phones with no internet, no cell signal and no server. Messages hop phone to phone over Bluetooth.

## Inspiration

In a blackout, a flood or a packed stadium, the network is the first thing to go. Phones still have Bluetooth. We wanted people to keep talking when the towers are down, without trusting the strangers whose phones carry their messages.

## What it does

- Phones in range link over Bluetooth LE and form a mesh. A message to someone out of range hops through phones in between.
- **Direct messages** are encrypted end to end. The phones that relay them cannot read them. The sender sees "Delivered, 2 hops via B" when the recipient's signed receipt comes back.
- **Public posts** reach everyone nearby, up to 6 hops.
- **Group chats** of up to 16 people. A group message goes out as one encrypted DM to each member, so relays still see only DMs, and the sender sees "Delivered to 3 of 4". Only the group's owner can change who is in it.
- **Any number of phones.** Each phone keeps up to 5 direct links; more phones simply add more hops.
- **Missed messages catch up.** When a phone comes back in range, neighbours compare message lists and fill the gap.
- **Attacks are caught and shown.** Every message is signed. A relay that changes a message is caught at the next phone. A key that floods posts is banned, with proof other phones can check. The Security tab shows each rejection as it happens.
- **Built-in red-team buttons** (tamper, drop, flood, spoof, replay) so you can attack the mesh live and watch it hold.
- **Photo DMs** (downscaled to under 40 KB), **QR code** of your identity, and **guest access**: a phone without the app can join from a browser over the host phone's hotspot.
- **Notifications for incoming DMs with inline reply**, so you can answer from the notification shade.

## How we built it

- **Protocol core** in Kotlin, shared by the Android app and a desktop node. Identity is an EC key pair; the ID is a hash of the public key. ECDSA signatures, ECDH plus AES-256-GCM for DMs, a small proof of work per message, a numbered slot system for the post rate limit, and an ingress pipeline that runs cheap checks before expensive ones.
- **Routing:** broadcasts go once per link and never back to a neighbour that has them. DMs follow learned routes and receipts walk the same path back.
- **Android:** a foreground service runs Bluetooth LE L2CAP channels (connection-oriented, up to 60 KB frames) and a WebView UI.
- **UI:** one plain HTML file with inline CSS and JavaScript, about 27 KB, no frameworks, no build step, no web fonts. Light and dark themes follow the phone, and it stays quick on a mid-range phone's WebView.
- **Desktop node** over TCP, used as the fallback demo and for testing.
- **Simulator:** an in-memory network with virtual time runs the real core code. Tests cover the acceptance checks plus red-team cases: forged receipts, presence spam, slot reuse across restarts, oversized and truncated frames, sync amplification.
- One acceptance script (`scripts/e2e.sh`) drives three or more nodes over HTTP and checks eight goals: two-hop DM, relay cannot read it, post arrives once, tampering rejected, flood banned, catch-up after reconnect, group message delivered to every member, post reaches every node.

## Challenges we ran into

- **Punishing the right party.** Anyone can put someone else's public key on a forged message. So a bad signature penalises the link that delivered it, never the named sender. A key is banned only on cryptographic proof: two signed posts for the same slot.
- **Restarts.** A phone that restarts could reuse a post slot and get itself banned. It now waits for the next 10-second epoch before posting.
- **Sync amplification.** Our own red-team tests found that one request could pull 512 copies of a message. Fixed in core before the demo.
- **Bluetooth on emulators.** Only one Bluetooth simulator may run, so emulators must start in a set order.

## Accomplishments we're proud of

- All eight acceptance checks pass on desktop nodes (3, 4 and 5) and on four Android emulators talking over emulated Bluetooth.
- Five desktop nodes pass all eight checks, including a group of all five delivered to 4 of 4 in about a second.
- One public post on a 25-phone simulated mesh with 40 links took 41 transmissions, about one per link.
- An honest list of what we do not defend against (`docs/THREATS.md`).

## What we learned

- Proof of work is a weak defence: a laptop mines about 165 posts per second per core at our difficulty. Rate limits with signed slots do the real work.
- Bluetooth links are point-to-point, so "stay silent if a neighbour already relayed" tricks do not apply.

## Honest limits

- Tested on desktop and emulators; **not yet on the real phones** at the time of writing.
- No protection against one person making many identities.
- Relays see who talks to whom, message sizes and timing.
- No forward secrecy: a stolen key decrypts past DMs.
- Android only; iPhones join as browser guests, and guest posts are signed by the host phone.
- On a desk all phones are in range, so the demo forces two hops in software.

## What's next

- Run the full test on the three real phones and measure range and timing.
- Forward secrecy and in-app QR scanning.

## Built with

Kotlin, Android (Bluetooth LE L2CAP, foreground service, WebView), Ktor, kotlinx.serialization, ZXing, Java security (ECDSA, ECDH, AES-GCM, SHA-256), Gradle, bash, curl, jq.
