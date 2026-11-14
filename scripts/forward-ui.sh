#!/usr/bin/env bash
# Forward each device's UI port 8080 to laptop ports 8081, 8082, 8083... in `adb devices` order (plan 10.3).
set -u
port=8081
for s in $(adb devices | tr -d '\r' | awk 'NR>1 && $2=="device" {print $1}'); do
    if adb -s "$s" forward tcp:$port tcp:8080 >/dev/null; then echo "$s -> http://127.0.0.1:$port/"; else echo "$s: forward failed"; fi
    port=$((port + 1))
done
