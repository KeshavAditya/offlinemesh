# UI structure

How the one-file page (`uiserver/src/main/resources/web/index.html`) is laid out, and which app conventions it follows. This replaces the four-tab table in guide section 13.3 (Feed, Chats, Network, Security). The JSON protocol and `window.OM_BACKEND` are unchanged.

## Sitemap

```
Tabs (bottom bar on phones, row under the top bar from 600 px)
├─ Chats  (home)   search + New chat; conversations newest first; then "In range, no chat yet"
│   ├─ New chat          New group (name + members) · list of people in range
│   └─ Conversation      name, verified badge, route line, hop-path strip; Photo / Message / Send
│       └─ Info          Contact info: Message, fingerprint, Verified, Muted, route
│                        Group info:   Message, members, Edit members (owner only)
├─ Feed            one line saying posts are public; posts; compose bar
├─ People          everyone the node knows about, with verified / muted / suspect / revoked
│   └─ Contact info      (same screen as above)
└─ Me              name, my fingerprint, my QR, Settings (Android app only), guest PIN and QR (while guest mode is on)
    ├─ Security and demo attacks   tamper / drop switches, flood / spoof / replay, event log
    ├─ Network and statistics      links (tree, description), mission profile, statistics
    └─ Panic wipe                  bottom of the screen, behind typing WIPE

Over everything: first-run welcome (name, fingerprint, two lines on what the app does),
"Reconnecting…" bar, and a strip showing the newest unseen security event (tap: opens the log).
```

The attack console is two taps from anywhere (Me, then Security and demo attacks). A caught attack is zero taps: the strip appears under the top bar on every screen and the Me tab gets a badge.

## Conventions adopted

| Convention | Where it comes from | What the page does |
|---|---|---|
| Three to five top-level destinations in a bottom bar, icon plus a one-word label, never hidden or disabled | [HIG Tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars): "Use a tab bar to support navigation, not to provide actions"; [Android navigation bar](https://developer.android.com/develop/ui/compose/components/navigation-bar): "Three to five destinations of equal importance" | Four tabs. Actions (New chat, Send, attacks) live in the screens, not in the bar. |
| Home is the conversation list; a conversation is a pushed screen with a back button | Signal, WhatsApp, Telegram, iMessage; [Briar manual](https://briarproject.org/manual/): home is the contact list, tapping a name opens the conversation | Chats is the first tab. DMs and groups share one list, newest first. |
| The tab bar gives way to the compose bar inside a conversation | Signal, WhatsApp, iMessage, Telegram | On phones the tabs hide in a conversation only; every other pushed screen keeps them. |
| Tap the conversation header for contact or group info; verification lives there | Signal (contact, then "View safety number", then "Mark as verified"), WhatsApp (contact info, then Encryption) | Info button in the conversation header. Contact info holds the fingerprint and the Verified switch. |
| One search, in a primary position, with its scope stated | [HIG Searching](https://developer.apple.com/design/human-interface-guidelines/searching): "Aim to make your app's content searchable through a single location" | Search field at the top of Chats covers chats, last messages and people in range. |
| One clear "create" action on the home screen | [Android FAB](https://developer.android.com/develop/ui/compose/components/fab): "a single, focused action that is the most common pathway"; iMessage puts compose beside search | A labelled **New chat** button beside search; New group is the first row inside it (Signal, WhatsApp). |
| Badges for what is new | HIG Tab bars: "Use a badge to indicate that critical information is available" | Unread counts on Chats, Feed and each chat row; unseen security events on Me. |
| Settings: few, general, rarely changed, away from the main task | [HIG Settings](https://developer.apple.com/design/human-interface-guidelines/settings): "Put general, infrequently changed settings in your custom settings area" | Me holds profile mode, links, statistics, the security log and the wipe. Per-person options (Verified, Muted) stay with the person. |
| Destructive actions need a deliberate confirmation | [HIG Alerts](https://developer.apple.com/design/human-interface-guidelines/alerts); Signal and WhatsApp put "Delete account" last in settings | Panic wipe is the last thing on Me and needs `WIPE` typed. |
| Short onboarding that asks only for what is needed | [HIG Onboarding](https://developer.apple.com/design/human-interface-guidelines/onboarding): "design a brief, enjoyable experience that doesn't require people to memorize a lot"; "Postpone nonessential setup" | One screen: two lines, the name (prefilled), the fingerprint, Start. Shown once per identity, so again after a wipe. |
| Empty screens say what to do next | [HIG Writing](https://developer.apple.com/design/human-interface-guidelines/writing) | Every list has an empty line that names the next step. |
| Back gesture closes the pushed screen | iOS edge swipe, Android system back | Pushed screens are browser history entries, so Safari's swipe and the browser back button close them. |

Sources read for this: the Apple HIG pages above (local copy, June 2026), the Android developer pages for the navigation bar and FAB, and the Briar manual. The Material 3 site and Signal's support pages could not be fetched (script-only page, HTTP 403); what is said about Signal, WhatsApp, Telegram and iMessage is from using the apps, not from a page read during this work.

## Deliberately not copied

- **A floating action button.** A labelled button beside search does the same job without covering the last list row or fighting the fixed compose bar in a WebView.
- **A separate safety-number screen.** Signal needs one because its contact screen is long. Ours is short, so the fingerprint and the Verified switch sit on Contact info, one tap closer.
- **Contacts hidden behind "New chat" only.** People appear by radio range, not from an address book, so Chats lists them under "In range, no chat yet" and People is a tab of its own (Telegram and Briar do the same).
- **Split view on wide screens** (HIG: tab bar becomes a sidebar). The laptop page stays one 680 px column; it is a demo console, and one layout is less to test.
- **Sheets, swipe actions on rows, long-press menus, large collapsing titles, animated transitions.** Each needs gesture code the page does not otherwise have.
- **Security buried in settings.** Chat apps hide it; here it is the demo, so it gets a badge and an always-visible strip.
- **An in-app QR scanner.** Needs camera access, which plain-HTTP Safari does not grant. The other phone's camera app reads the QR text.

## Android bridge (Me > Settings)

The app's WebView fills the screen; the old native switch row is gone. `MainActivity.Bridge` is exposed to the page as `OMNative` (`addJavascriptInterface`; navigation is pinned to `http://127.0.0.1:8080/` and the bridge answers only while that page is loaded):

- `OMNative.state()` returns JSON `{ble, lan, guest, secure, status}`; `status` is the blocker text ("Turn on Bluetooth.") or empty.
- `OMNative.set(key, on)` for `ble`, `lan`, `guest`, `secure`; `OMNative.open(what)` for `invite`, `qr`, `share`, `bundle`, `keepalive`. Other values are ignored.
- The app calls the page's `omNative()` after a native-side change, and `omBack()` on system Back (true: a pushed screen was closed; false: leave the app).

The page builds one `li.set-row` per item in `#natRows` (switch rows reuse `label.sw`, tap rows reuse `.chat` plus a chevron) and shows `#nat` only when `window.OMNative` exists, so a desktop browser and the browser guest client never see it. The blocker text shows in the `#conn` bar under the top bar.

## Follow-ups (not done here)

- The welcome screen is skipped in iframes (the booth page) and when `navigator.webdriver` is set. A test that drives a normal browser must press Start once or set `localStorage.omWelcome` to the node id.

## Modern pass

Audit of the page as it stood (64,847 bytes on `v2`, 66,651 with the Settings rows), done with the `impeccable` skill (detector plus its audit, distill and quieter passes, Operate mode) and the `emil-design-eng` motion review, before any edit. No PRODUCT.md or DESIGN.md exists, so the owner's brief is the direction.

### What is wrong today

- **Accent everywhere.** Teal carries eleven jobs (avatars, link pill, hop-path pills, tab highlight, verified disc, sender names, links, badges, borders of pinned posts and the guest box, primary buttons, my bubbles), so it marks nothing. Two more state colours (amber, red) each have a tinted background as well.
- **Boxes instead of hierarchy.** Feed posts are stacked rounded cards; Me, Security and Network are stacks of bordered boxes, two red tinted fieldsets, a grid of nine stat tiles, and log entries with a coloured side stripe (as has the alert strip). The detector flags the tinted compose shadow (glow on dark) and text flush against box borders.
- **Decoration that says nothing.** Initials avatars all in one tint (the letter repeats the name beside it), the CSS-drawn brand mark, the "1 link" pill with a dot, hop pills joined by lines that repeat the words "1 hop" next to them, `Tag` pills for Muted / Owner / You / Pinned, `›` and `✓ ✓✓ •` glyphs and an emoji standing in for icons, and "A · a3f2 9c01" as a subtitle on every screen.
- **Placement.** Three left edges on one screen (rows bleed 8 px, boxes inset 12 px, text at 16 px); chat rows are three lines tall; the Security screen shrinks its text below 13 px to fit the tiles; the contact screen is centred while every other screen is left-aligned; the bundle export link is a 24 px target; the map labels overlap.
- **Type.** Eleven sizes and weights 500/600/650/700/750 with no clear steps.
- **Motion.** None for navigation, and the switch animates `left` (layout) instead of a transform.

| Before | After | Why |
| --- | --- | --- |
| Screens swap with no transition | Pushed screen: `opacity` + `translateX(12px)`, 180 ms, `cubic-bezier(.23,1,.32,1)`; tabs and Back stay instant | Push is occasional and spatial; tabs are high-frequency, so no animation |
| `transition: background-color, color, border-color .15s` on every button | `:active` changes at once; `transform: scale(.97)` with a 120 ms ease-out release | Press feedback must be immediate |
| Switch thumb animates `left` | `transform: translateX()` 150 ms ease-out | Transform and opacity only |
| No reduced-motion rule | Movement sits inside `prefers-reduced-motion: no-preference`; colour changes stay | Fewer and gentler, not broken |
| New message: nothing | Nothing (lists are rebuilt on every event) | No animation on high-frequency updates |

### Removed

Avatars and the brand mark; every pill and tag (state becomes plain words); hop-path pills (the route is a line of text: "You → Carlos → Bea"); card borders, boxes, tinted fieldsets, side stripes, stat tiles (counters become a two-column list); amber and both tints; the compose shadow; glyph and emoji icons (the verified mark and chevrons are drawn in CSS, receipts use words, "I'm safe" replaces the emoji on screen while the signed text on the wire is unchanged); the per-screen identity subtitle; the People intro line; the third line of chat rows; the hand-rolled modal for the welcome screen (now `<dialog>`).

### Kept

Every screen and feature in the sitemap; the bottom tab bar with icon and label; bubbles with mine in the accent; unread badges; the verified mark; red text for suspect / revoked / late roll call; the security strip and the Me badge; search beside New chat; the custom switch (Settings rows need one); the map; typed confirmation for the wipe; `window.OM_BACKEND`, the JSON protocol, `OMNative`, and the ids and classes `webbackend.js` uses (`box`, `row`, `pri`, `mut`, `mono`, `qr`, `#profile` and its two neighbours, the `fieldset` around `#tamper`, `#guest`).

### Direction

1. A calm, native-feeling messenger: system font, neutral surfaces, the existing teal as the only accent, used for primary buttons, my bubbles, the active tab, badges and the verified mark.
2. Hierarchy from four type sizes (13 / 16 / 17 / 22), two weights (400 / 600) and a 4 / 8 / 12 / 16 / 24 spacing scale; plain rows with hairline dividers; one left edge at 16 px.
3. One filled button per screen, always at the right end of the row it acts on (New chat, Send, Post) or full width under a heading (Message, Start); everything else is quiet.
4. State in words, errors in red next to the thing that failed, every target 44 px or more, a 640 px centred column on wide screens.
5. Motion only where it explains: a 180 ms push, an immediate press; less code and fewer bytes than before.
