#!/usr/bin/env bash
# Booth screen: forward up to 3 nodes and open scripts/booth.html with their UIs side by side (A, B relay, C).
#   scripts/booth.sh                         forward every adb device (scripts/forward-ui.sh), show 8081 8082 8083
#   scripts/booth.sh <serialA> <serialB> <serialC>   forward those phones to 8081..8083 in that order
#   scripts/booth.sh 8081 8082 8083          ports only (desktop nodes or forwards already made); mixing works too
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
[ $# -gt 3 ] && { echo "at most 3 nodes"; exit 2; }
ports=()
if [ $# -eq 0 ]; then
    "$DIR/forward-ui.sh"
    ports=(8081 8082 8083)
else
    i=0
    for a in "$@"; do
        p=$((8081 + i))
        if [[ "$a" =~ ^[0-9]+$ ]]; then p=$a
        elif adb -s "$a" forward tcp:$p tcp:8080 >/dev/null; then echo "$a -> http://127.0.0.1:$p/"
        else echo "$a: forward failed"; fi
        ports+=("$p"); i=$((i + 1))
    done
fi
for p in "${ports[@]}"; do
    curl -s --max-time 2 "http://127.0.0.1:$p/api/snapshot" >/dev/null || echo "warning: nothing answers on :$p yet"
done
q=$(IFS=,; echo "${ports[*]}")
# Windows `start` drops the ?query of a file: URL, so open a tiny redirect page that carries it.
launch="${TEMP:-/tmp}/offlinemesh-booth.html"
echo "<meta http-equiv=\"refresh\" content=\"0;url=file:///$(cygpath -m "$DIR" | sed "s/ /%20/g")/booth.html?p=$q\">" > "$launch"
echo "opening booth.html?p=$q"
cmd //c start "" "$(cygpath -w "$launch")"
