#!/usr/bin/env bash
# Three desktop nodes on this machine wired in a line A<->B<->C over TCP (discovery off), UIs on 8081..8083.
#   scripts/run-3-desktop.sh          build, wipe .mesh-run/, start A, B, C (stops old ones first)
#   scripts/run-3-desktop.sh stop     stop them
# Logs: .mesh-run/{A,B,C}.log   Data: .mesh-run/{A,B,C}/   PIDs: .mesh-run/{A,B,C}.pid
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/.mesh-run"
BIN="$ROOT/desktop/build/install/desktop/bin/desktop"

stop() {
    for n in A B C; do
        f="$RUN/$n.pid"
        [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null
        rm -f "$f"
    done
    # Wait for the UI ports to close so a restart does not hit "address in use".
    for p in 8081 8082 8083; do
        for _ in $(seq 1 20); do curl -s --max-time 1 "http://127.0.0.1:$p/api/snapshot" >/dev/null || break; sleep 0.5; done
    done
}

if [ "${1:-}" = "stop" ]; then stop; echo "stopped"; exit 0; fi

stop
# Another run (other checkout or agent) on these ports would answer our readiness checks and e2e would test it instead.
for p in 8081 8082 8083; do
    curl -s --max-time 1 "http://127.0.0.1:$p/" >/dev/null && { echo "port $p is already in use by another process; stop it first"; exit 1; }
done
mkdir -p "$ROOT/build"
(cd "$ROOT" && ./gradlew :desktop:installDist -q > "$ROOT/build/run-3-desktop-build.log" 2>&1) || {
    echo "build failed, see build/run-3-desktop-build.log"; tail -20 "$ROOT/build/run-3-desktop-build.log"; exit 1; }
rm -rf "$RUN"; mkdir -p "$RUN"

# Only one side of each pair dials (--peer), otherwise each pair gets two links.
start() {  # name uiPort listenPort [args...]
    local n=$1 ui=$2 listen=$3; shift 3
    "$BIN" --data "$RUN/$n" --ui-port "$ui" --listen "$listen" --no-discovery "$@" > "$RUN/$n.log" 2>&1 &
    echo $! > "$RUN/$n.pid"
}
start A 8081 9001
start B 8082 9002 --peer 127.0.0.1:9001
start C 8083 9003 --peer 127.0.0.1:9002

for p in 8081 8082 8083; do
    ok=
    for _ in $(seq 1 60); do
        curl -s --max-time 1 "http://127.0.0.1:$p/api/snapshot" >/dev/null && { ok=1; break; }
        sleep 0.5
    done
    [ -n "$ok" ] || { echo "node on :$p did not come up; logs in $RUN"; stop; exit 1; }
done
# The node answering each port must be ours: its id is the one our process printed.
for x in A:8081 B:8082 C:8083; do
    n=${x%:*} p=${x#*:}
    id=$(sed -n 's/^node \([0-9a-f]\{16\}\) .*/\1/p' "$RUN/$n.log" | head -1)
    curl -s --max-time 2 "http://127.0.0.1:$p/api/snapshot" | grep -q "\"id\":\"$id\"" && [ -n "$id" ] ||
        { echo "node $n is not the one answering :$p (bind failed?); see $RUN/$n.log"; stop; exit 1; }
done
echo "A http://127.0.0.1:8081  B http://127.0.0.1:8082  C http://127.0.0.1:8083  (stop: $0 stop)"
