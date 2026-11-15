#!/usr/bin/env bash
# UNTESTED ON HARDWARE — not part of the demo build
# Copies the OfflineMesh web client into portal/data/ for the LittleFS upload.
# index.html gets the same edit GuestServer makes at runtime: load webbackend.js (the in-browser node) first.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
web="$here/../../../uiserver/src/main/resources/web"
out="$here/portal/data"
mkdir -p "$out"
for f in webbackend.js meshnode.js meshcore.js noble.js; do cp "$web/$f" "$out/$f"; done
awk '!done && index($0, "<script>") {
  sub(/<script>/, "<script>window.OM_APK = null;</script>\n<script src=\"/webbackend.js\"></script>\n<script>"); done = 1
} 1' "$web/index.html" > "$out/index.html"
grep -q 'src="/webbackend.js"' "$out/index.html" || { echo "copy-web: <script> tag not found in index.html" >&2; exit 1; }
du -ch "$out"/* | tail -1
