<!-- UNTESTED ON HARDWARE — not part of the demo build -->
# Portal box: a captive-portal access point for OfflineMesh guests

**Untested on hardware. Not part of the demo build.** Neither the ESP32 sketch nor the Pi script has been compiled, flashed or run. The demo uses the stock phone hotspot plus QR codes, which needs no extra hardware.

Why bother: a stock Android hotspot cannot be a captive portal. AOSP's tethering DHCP server passes `null /* captivePortalApiUrl */` and its DNS is a fixed system `dnsmasq`. A box you control can answer every DNS query with its own address, which is what makes phones pop up "Sign in to network" and open our page without typing a URL.

## What each one does

| | ESP32 (`esp32/`) | Raspberry Pi (`pi/`) |
|---|---|---|
| Wi-Fi | WPA2 AP, passphrase you set at compile time (16+ characters enforced) | WPA2 AP, random 24-character passphrase generated at setup, `ap_isolate=1` |
| Captive DNS | `DNSServer` answers every name with 192.168.4.1 | `dnsmasq` with `address=/#/10.42.0.1`, no upstream |
| Web | Serves the web client from LittleFS; probe paths and unknown URLs redirect to `/` | Desktop node's guest server on :8090; iptables redirects port 80 to it |
| Mesh host | **No.** It has no protocol core. See below. | **Yes.** A full desktop node (Kotlin core) as a systemd service |
| Effort | 2-4 h with the board in hand | About half a day |

### ESP32

1. `esp32/copy-web.sh` copies `index.html`, `webbackend.js`, `meshnode.js`, `meshcore.js`, `noble.js` from `uiserver/src/main/resources/web/` into `esp32/portal/data/`, and makes the same edit to `index.html` that the guest server makes at runtime (load `webbackend.js`, no APK). About 176 KB, which fits the default LittleFS partition.
2. Set `AP_PASS` in `portal.ino` to 16 or more random characters (the build fails otherwise).
3. Arduino IDE 2 with the esp32 core: open `esp32/portal/portal.ino`, upload the sketch, then upload `data/` with the LittleFS upload tool (the "arduino-littlefs-upload" plugin). PlatformIO works too with `board_build.filesystem = littlefs`.

**The ESP32 cannot host the mesh.** The web client is a leaf: it needs a `/mesh` WebSocket on a real node (phone app or desktop node) that runs the Kotlin core and relays. Options:

- **Redirect to a host on the AP (implemented, one define).** A laptop running the desktop node with `--guest-port 8090`, or a phone, joins the ESP32's Wi-Fi with a fixed address. Set `#define MESH_HOST "192.168.4.2"` and `/` redirects there; that node serves the full client and `/mesh` itself. The ESP32 is then only Wi-Fi + captive DNS.
- **No host (default).** The page loads from flash; `/mesh` answers 503 "no mesh host on this access point". The page keeps retrying and, after three failures, asks for a PIN. Useful only to show the page or hand out instructions.
- **WebSocket proxy on the ESP32 (not implemented).** Bridge `/mesh` frames to a host node elsewhere on the AP. Possible with an async WebSocket server plus a TCP client, but it adds a hop that can read and drop frames (it cannot forge them: they are signed) and is more work than the redirect for no gain.

### Raspberry Pi

```
./gradlew :desktop:installDist                   # on your computer
scp -r desktop/build/install/desktop extras/portal-box/pi/setup.sh pi@<pi>:
sudo ./setup.sh ~/desktop                        # on the Pi (Raspberry Pi OS Bookworm)
```

It installs hostapd, dnsmasq, iptables and Java 17, copies the node to `/opt/offlinemesh`, runs it as user `offlinemesh` (`--data /var/lib/offlinemesh --ui-port 8081 --listen 9001 --guest-port 8090`), takes `wlan0` away from NetworkManager, sets `10.42.0.1/24`, and prints the SSID, passphrase and a `WIFI:` QR string. The guest PIN is in `journalctl -u offlinemesh | grep pin`. The owner UI stays on the Pi's localhost: `ssh -L 8081:localhost:8081 pi@<pi>`. Laptops on the AP can also peer with the Pi's node over TCP 9001.

**Known gap:** the guest server answers unknown paths with 404, so the OS probes (`/generate_204`, `/hotspot-detect.html`) get 404 through the port 80 redirect. Android then reports "connected, no internet" instead of the sign-in pop-up, and an iPhone sheet may show the 404. Guests can still open `http://10.42.0.1/` from the QR. The fix is one catch-all route in `GuestServer` that redirects unknown GETs to `/` (owned by mesh-ui; noted in `STATUS.md`). The ESP32 sketch already does this.

## Captive-portal behaviour on phones (from `research/ADVANCES.md` 2.4 and `research/notes/4_https_captive.md`)

- What raises the pop-up: answering all DNS with the box's address and redirecting HTTP to the portal page. The OS probes are `http://captive.apple.com/hotspot-detect.html` (iOS), `http://connectivitycheck.gstatic.com/generate_204` (Android, expects 204), `www.msftconnecttest.com/connecttest.txt` (Windows).
- The modern route (DHCP option 114, RFC 8910/8908) needs an HTTPS API endpoint with a certificate the phone can validate. Offline with a self-signed or IP-literal URL, compliant phones ignore it. We do not use it.
- **iOS sign-in sheet is a crippled browser:** no persistent cookies (storage is wiped when it closes), it closes when you switch apps, and "Cancel" disconnects from the Wi-Fi. A guest's in-browser key would be lost every time. So the page in the sheet should say "open http://10.42.0.1/ in Safari" and the real use happens in Safari. Note the origin also matters for keys: a key saved under `http://captive.apple.com` is not the one saved under `http://10.42.0.1`.
- Android opens its own captive portal view, then the user can continue in Chrome. Phones with "Private DNS" set to a specific provider will not use our DNS and see no pop-up; "Automatic" falls back to plain DNS.
- Honest verdict from the research: likely no better than a printed QR code, which is what the demo uses.

## HTTPS: the options and why we chose plain HTTP

| Option | Result |
|---|---|
| Public certificate for a private IP or `.local` | Not possible: CAs "SHALL NOT issue Certificates containing Internal Names or Reserved IP Addresses" (CA/B Baseline Requirements). |
| Real domain + public certificate, Pi answers that name with its own IP | The only warning-free padlock for an unprepared guest. The private key sits on the Pi; the Pi must get online to renew (certificates last 200 days now, 100 from March 2027, 47 from March 2029). Offline behaviour unverified. A day of work. |
| Self-signed certificate | Full-page warning that trains guests to click through; Chrome reportedly still refuses service workers on it. Not worth it. |
| Own CA, root profile installed on the guest's iPhone | A multi-step Settings procedure, and the CA owner can then intercept all of that phone's TLS. No for guests. |
| Plain HTTP by IP (chosen) | Not a secure context: no `crypto.subtle`, no service worker, so the client ships its own crypto (`noble.js`). Messages are still signed and end-to-end encrypted in the page. Chrome excludes private IPs from its HTTPS-by-default change. |

## Threat note: the host serves the JavaScript

Whoever runs the box serves the code that holds the guest's key. A malicious or compromised ESP32, Pi or redirect target can send a page that leaks the key or signs what it likes, and plain HTTP means nothing checks the code. Signing and encryption protect messages from relays, not the guest from the page's host. Guests who need more should install the app (whose APK hash the host shows) and verify contacts by QR. See `docs/THREATS.md`.

Specific to these boxes:
- WPA2 with a shared passphrase lets any guest who knows it attack other guests' plain HTTP (ARP spoofing, then rewriting the JS). The Pi sets `ap_isolate=1` so stations cannot reach each other. The ESP32 sketch does not set station isolation; treat everyone on its Wi-Fi as able to tamper with the page.
- The captive DNS answers every name, so the box can serve a page under any `http://` origin. That is how captive portals work; it is also why guests should not log in to anything else while joined.
- The Pi's identity key is in `/var/lib/offlinemesh` (mode 0700, user `offlinemesh`). Physical access to the SD card is access to the key.
