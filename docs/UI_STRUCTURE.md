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
└─ Me              name, my fingerprint, my QR, guest PIN and QR (while guest mode is on)
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

## Follow-ups (not done here)

- The native Android switch row (Bluetooth, Wi-Fi LAN, Guest mode, QR hop, Share app in `MainActivity.kt`) stays native: there is no JS-to-native bridge. With one (`addJavascriptInterface`), these belong under Me.
- `MainActivity` does not forward the system back button to the WebView. Adding `if (web.canGoBack()) web.goBack()` makes Android back close pushed screens; the page side is already in place.
- The welcome screen is skipped in iframes (the booth page) and when `navigator.webdriver` is set. A test that drives a normal browser must press Start once or set `localStorage.omWelcome` to the node id.
