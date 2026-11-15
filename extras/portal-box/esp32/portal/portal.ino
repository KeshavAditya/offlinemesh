// UNTESTED ON HARDWARE — not part of the demo build
//
// OfflineMesh portal box, ESP32 (Arduino core 2.x/3.x, no extra libraries: WiFi, DNSServer, WebServer, LittleFS are built in).
// Based on the captive-portal pattern of the official examples (arduino-esp32 DNSServer/CaptivePortal and
// ESP-IDF protocols/http_server/captive_portal): answer every DNS query with our own IP and redirect every unknown
// HTTP path to "/", which is what makes Android, iOS and Windows show the "sign in to network" pop-up.
//
// The ESP32 cannot run a mesh host (the protocol core is Kotlin). It only serves the web client from LittleFS.
// The page then needs a /mesh WebSocket host; see ../../README.md for the options. Without MESH_HOST the page
// loads, its socket gets 503 and it keeps retrying (after 3 failures it asks for a PIN).
//
// Files: run ../copy-web.sh first (copies the web client into portal/data/), then upload data/ as LittleFS.

#include <WiFi.h>
#include <DNSServer.h>
#include <WebServer.h>
#include <LittleFS.h>

#define AP_SSID "OfflineMesh"
#define AP_PASS "CHANGE-ME-16-or-more-random-chars"   // WPA2 passphrase; set a random one before flashing
// A laptop or phone node joined to this AP with a fixed IP. When set, "/" redirects there and that node serves the
// full client and /mesh itself. Leave undefined to serve the page from flash only.
// #define MESH_HOST "192.168.4.2"
#define MESH_PORT "8090"

static_assert(sizeof(AP_PASS) - 1 >= 16, "AP_PASS must be at least 16 characters");

const IPAddress apIP(192, 168, 4, 1);
const IPAddress apMask(255, 255, 255, 0);
DNSServer dns;
WebServer server(80);

static void redirect(const String& to) {
  server.sendHeader("Location", to, true);
  server.sendHeader("Cache-Control", "no-store");
  server.send(302, "text/plain", "");
}

static void sendIndex() {
  File f = LittleFS.open("/index.html", "r");
  if (!f) { server.send(500, "text/plain", "index.html missing: run copy-web.sh and upload data/ to LittleFS"); return; }
  server.sendHeader("Cache-Control", "no-store");
  server.streamFile(f, "text/html");
  f.close();
}

void setup() {
  Serial.begin(115200);
  if (!LittleFS.begin()) Serial.println("LittleFS mount failed: upload data/ first");

  WiFi.mode(WIFI_AP);
  WiFi.softAPConfig(apIP, apIP, apMask);
  // channel 1, not hidden, up to 8 stations (the ESP32 limit is 10)
  if (!WiFi.softAP(AP_SSID, AP_PASS, 1, 0, 8)) Serial.println("softAP failed");
  Serial.printf("AP %s up at %s\n", AP_SSID, apIP.toString().c_str());

  dns.setErrorReplyCode(DNSReplyCode::NoError);
  dns.start(53, "*", apIP);   // every name resolves to us: this is what raises the captive pop-up

#ifdef MESH_HOST
  server.on("/", [] { redirect("http://" MESH_HOST ":" MESH_PORT "/"); });
#else
  server.on("/", sendIndex);
#endif
  // The web client opens ws://<host>/mesh. This board has no mesh core, so say so instead of upgrading.
  server.on("/mesh", [] { server.send(503, "text/plain", "no mesh host on this access point"); });
  server.on("/app.apk", [] { server.send(404, "text/plain", "no app here"); });
  server.serveStatic("/webbackend.js", LittleFS, "/webbackend.js", "no-cache");
  server.serveStatic("/meshnode.js", LittleFS, "/meshnode.js", "no-cache");
  server.serveStatic("/meshcore.js", LittleFS, "/meshcore.js", "no-cache");
  server.serveStatic("/noble.js", LittleFS, "/noble.js", "no-cache");
  // OS probes (/generate_204, /hotspot-detect.html, /connecttest.txt) and every other path: send them to the page.
  server.onNotFound([] { redirect(String("http://") + apIP.toString() + "/"); });
  server.begin();
}

void loop() {
  dns.processNextRequest();
  server.handleClient();
}
