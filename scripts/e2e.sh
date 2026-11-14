#!/usr/bin/env bash
# Acceptance checks 1-6 (plan section 1) against three running nodes, driven over their UI ports with curl + jq.
#   scripts/e2e.sh 8081 8082 8083      (desktop nodes from run-3-desktop.sh, or phones through adb forward)
# A and C are muted toward each other first (5.9) so every A<->C message must go through B.
# Prints PASS/FAIL per item; exits 1 if any item fails.
set -u
[ $# -eq 3 ] || { echo "usage: $0 <portA> <portB> <portC>"; exit 2; }
PA=$1 PB=$2 PC=$3
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_B="${E2E_LOG_B:-$ROOT/.mesh-run/B.log}"   # B's log, checked for plaintext when present (desktop run)
T=${E2E_TIMEOUT:-90}                           # seconds per wait; PoW mining is slow on phones

if ! command -v jq >/dev/null 2>&1 && [ -n "${LOCALAPPDATA:-}" ]; then
    for d in "$(cygpath -u "$LOCALAPPDATA" 2>/dev/null || echo "$LOCALAPPDATA")"/Microsoft/WinGet/Packages/jqlang.jq_*/; do PATH="$PATH:$d"; done
fi
command -v jq >/dev/null 2>&1 || { echo "jq not found"; exit 2; }

snap() { curl -s --max-time 5 "http://127.0.0.1:$1/api/snapshot"; }
cmd() { curl -s --max-time 5 -X POST "http://127.0.0.1:$1/api/cmd" -d "$2" >/dev/null; }
# q <port> <jq filter> [jq args...]: true when the filter yields true on that node's snapshot.
q() { local p=$1; shift; snap "$p" | jq -e "$@" >/dev/null 2>&1; }
val() { local p=$1; shift; snap "$p" | jq -r "$@" 2>/dev/null | tr -d '\r'; }
wait_until() { local end=$((SECONDS + $1)); shift; while [ $SECONDS -lt $end ]; do "$@" && return 0; sleep 1; done; return 1; }
feed_count() { val "$1" --arg t "$2" '[.feed[] | select(.text == $t)] | length'; }

FAILED=0
result() { if [ "$2" = ok ]; then echo "PASS $1"; else echo "FAIL $1: $2"; FAILED=1; fi; }

IA=$(val $PA .self.id); IB=$(val $PB .self.id); IC=$(val $PC .self.id)
echo "A=$IA B=$IB C=$IC"
for id in "$IA" "$IB" "$IC"; do
    [ ${#id} -eq 16 ] || { for i in 1 2 3 4 5 6; do result $i "node ids missing (is the core running?)"; done; exit 1; }
done
N=$RANDOM$$
cmd $PA '{"t":"name","name":"A"}'; cmd $PB '{"t":"name","name":"B"}'; cmd $PC '{"t":"name","name":"C"}'
cmd $PA "{\"t\":\"mute\",\"id\":\"$IC\",\"on\":true}"
cmd $PC "{\"t\":\"mute\",\"id\":\"$IA\",\"on\":true}"

banned_a() { q $1 --arg a "$IA" 'any(.security[]; .kind == "BANNED" and (.src == $a or (.detail | contains($a))))'; }

# 1. A -> C direct message arrives through B; A shows DELIVERED, 2 hops via B.
T1="e2e dm $N"
if wait_until $T q $PA --arg c "$IC" 'any(.contacts[]; .id == $c)'; then
    cmd $PA "{\"t\":\"dm\",\"to\":\"$IC\",\"text\":\"$T1\"}"
    delivered() { q $PA --arg c "$IC" --arg b "$IB" --arg t "$T1" \
        'any(.dms[$c][]?; .text == $t and .status == "DELIVERED" and .hops == 2 and .via == $b)'; }
    received() { q $PC --arg a "$IA" --arg t "$T1" 'any(.dms[$a][]?; .text == $t and .status == "RECEIVED")'; }
    if ! wait_until $T received; then result 1 "C never received the DM"
    elif ! wait_until $T delivered; then
        result 1 "A's DM not DELIVERED/2 hops/via B: $(val $PA --arg c "$IC" --arg t "$T1" '.dms[$c][]? | select(.text == $t) | "\(.status) hops=\(.hops) via=\(.via)"')"
    else result 1 ok; fi
else result 1 "A never learned contact C"; fi

# 2. B never shows the DM text (snapshot and, on desktop, its log).
if snap $PB | grep -qF "$T1"; then result 2 "plaintext in B's snapshot"
elif [ -f "$LOG_B" ] && grep -qF "$T1" "$LOG_B"; then result 2 "plaintext in $LOG_B"
else result 2 ok; fi

# 3. A's post appears on B and C exactly once each.
T3="e2e post $N"
cmd $PA "{\"t\":\"post\",\"text\":\"$T3\"}"
on_both() { [ "$(feed_count $PB "$T3")" -ge 1 ] && [ "$(feed_count $PC "$T3")" -ge 1 ]; }
if wait_until $T on_both; then
    sleep 3   # let duplicates (if any) arrive
    b=$(feed_count $PB "$T3"); c=$(feed_count $PC "$T3")
    if [ "$b" = 1 ] && [ "$c" = 1 ]; then result 3 ok; else result 3 "count B=$b C=$c"; fi
else result 3 "post did not reach B and C (B=$(feed_count $PB "$T3") C=$(feed_count $PC "$T3"))"; fi

# 6. (run before 5, which bans A on B) A posts while C is cut off; after reconnecting C has it within 20 s.
T6="e2e partition $N"
cmd $PB "{\"t\":\"mute\",\"id\":\"$IC\",\"on\":true}"
cmd $PC "{\"t\":\"mute\",\"id\":\"$IB\",\"on\":true}"
cmd $PA "{\"t\":\"post\",\"text\":\"$T6\"}"
on_b() { [ "$(feed_count $PB "$T6")" -ge 1 ]; }
on_c() { [ "$(feed_count $PC "$T6")" -ge 1 ]; }
if ! wait_until $T on_b; then r6="post never reached B"
elif on_c; then r6="C got the post while muted"
else
    cmd $PB "{\"t\":\"mute\",\"id\":\"$IC\",\"on\":false}"
    cmd $PC "{\"t\":\"mute\",\"id\":\"$IB\",\"on\":false}"
    s=$SECONDS
    if wait_until 20 on_c; then r6=ok; echo "  item 6: C caught up in $((SECONDS - s)) s"; else r6="not on C 20 s after reconnect"; fi
fi
cmd $PB "{\"t\":\"mute\",\"id\":\"$IC\",\"on\":false}"; cmd $PC "{\"t\":\"mute\",\"id\":\"$IB\",\"on\":false}"
result 6 "$r6"

# 4. B tampers; C rejects with a security log line; A is not banned.
T4="e2e tamper $N"
sec0=$(val $PC '[.security[] | select(.kind == "BAD_SIGNATURE" or .kind == "BAD_POW" or .kind == "MALFORMED")] | length')
cmd $PB '{"t":"debug","flag":"tamper","on":true}'
cmd $PA "{\"t\":\"post\",\"text\":\"$T4\"}"
rejected() { [ "$(val $PC '[.security[] | select(.kind == "BAD_SIGNATURE" or .kind == "BAD_POW" or .kind == "MALFORMED")] | length')" -gt "${sec0:-0}" ]; }
if ! wait_until $T rejected; then r4="no security line on C"
elif [ "$(feed_count $PC "$T4")" != 0 ]; then r4="C accepted the tampered post"
elif banned_a $PB || banned_a $PC; then r4="A's key got banned"
else r4=ok; fi
cmd $PB '{"t":"debug","flag":"tamper","on":false}'
result 4 "$r4"

# 5. Flood (4 posts in one epoch) bans A on B; C holds exactly the first three.
from_a() { val $PC --arg a "$IA" '[.feed[] | select(.from == $a)] | length'; }
stable() { local x; x=$(from_a); sleep 4; [ "$x" = "$(from_a)" ]; }
wait_until $T stable
c0=$(from_a)
# Start at the top of a fresh 10 s epoch so the four posts share one.
sleep $((10 - $(date +%s) % 10)); sleep 0.3
cmd $PA '{"t":"attack","kind":"flood"}'
if ! wait_until $T banned_a $PB; then r5="no BANNED line for A on B"
else
    wait_until $T stable
    c1=$(from_a)
    if [ $((c1 - c0)) -eq 3 ]; then r5=ok; else r5="C got $((c1 - c0)) flood posts, want 3"; fi
fi
result 5 "$r5"

exit $FAILED
