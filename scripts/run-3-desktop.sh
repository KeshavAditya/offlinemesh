#!/usr/bin/env bash
# Three desktop nodes on this machine wired in a line A<->B<->C over TCP (discovery off), UIs on 8081..8083 (or UI_BASE..UI_BASE+2).
#   scripts/run-3-desktop.sh          build, wipe .mesh-run/, start A, B, C (stops old ones first)
#   NODES=5 scripts/run-3-desktop.sh  A..E in a line instead (13.1; 3 to 10 nodes), UIs UI_BASE..UI_BASE+N-1
#   scripts/run-3-desktop.sh stop     stop them
# Logs: .mesh-run/{A,B,C}.log   Data: .mesh-run/{A,B,C}/   PIDs: .mesh-run/{A,B,C}.pid
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/.mesh-run"
U=${UI_BASE:-8081}   # UI_BASE=8181 avoids adb forwards on 8081-8083
N=${NODES:-3}
case "$N" in 3|4|5|6|7|8|9|10) ;; *) echo "NODES must be 3 to 10"; exit 2;; esac
NAMES=(A B C D E F G H I J); NAMES=("${NAMES[@]:0:$N}")
PORTS=(); for i in $(seq 0 $((N-1))); do PORTS+=($((U+i))); done
BIN="$ROOT/desktop/build/install/desktop/bin/desktop"

stop() {
    for f in "$RUN"/*.pid; do   # every node of the last run, whatever its NODES was
        [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null
        rm -f "$f"
    done
    # Wait for the UI ports to close so a restart does not hit "address in use".
    for p in ${PORTS[@]}; do
        for _ in $(seq 1 20); do curl -s --max-time 1 "http://127.0.0.1:$p/api/snapshot" >/dev/null || break; sleep 0.5; done
    done
}

if [ "${1:-}" = "stop" ]; then stop; echo "stopped"; exit 0; fi

stop
# Another run (other checkout or agent) on these ports would answer our readiness checks and e2e would test it instead.
for p in ${PORTS[@]}; do
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
start A ${PORTS[0]} 9001
for i in $(seq 1 $((N-1))); do start ${NAMES[$i]} ${PORTS[$i]} $((9001+i)) --peer 127.0.0.1:$((9000+i)); done

for p in ${PORTS[@]}; do
    ok=
    for _ in $(seq 1 60); do
        curl -s --max-time 1 "http://127.0.0.1:$p/api/snapshot" >/dev/null && { ok=1; break; }
        sleep 0.5
    done
    [ -n "$ok" ] || { echo "node on :$p did not come up; logs in $RUN"; stop; exit 1; }
done
# The node answering each port must be ours: its id is the one our process printed.
for i in $(seq 0 $((N-1))); do
    n=${NAMES[$i]} p=${PORTS[$i]}
    id=$(sed -n 's/^node \([0-9a-f]\{16\}\) .*/\1/p' "$RUN/$n.log" | head -1)
    curl -s --max-time 2 "http://127.0.0.1:$p/api/snapshot" | grep -q "\"id\":\"$id\"" && [ -n "$id" ] ||
        { echo "node $n is not the one answering :$p (bind failed?); see $RUN/$n.log"; stop; exit 1; }
done
line=; for i in $(seq 0 $((N-1))); do line="$line${NAMES[$i]} http://127.0.0.1:${PORTS[$i]}  "; done
echo "$line(stop: $0 stop)"
