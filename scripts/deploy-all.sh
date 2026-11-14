#!/usr/bin/env bash
# Install the debug APK on every connected device, grant runtime permissions, turn Bluetooth on, launch (plan 10.3).
#   ./gradlew :app:assembleDebug && scripts/deploy-all.sh [--fresh]
# --fresh wipes app data (new identities, no stored bans). Use it before each e2e.sh rerun: e2e item 5 bans A for
# 10 min by design, and phones keep that ban across restarts, so a rerun within 10 min fails items 1, 3 and 4.
set -u
FRESH=0; [ "${1:-}" = "--fresh" ] && FRESH=1
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
PKG=com.meshnet.offline
[ -f "$APK" ] || { echo "no APK at $APK; run ./gradlew :app:assembleDebug"; exit 1; }
SERIALS=$(adb devices | tr -d '\r' | awk 'NR>1 && $2=="device" {print $1}')
[ -n "$SERIALS" ] || { echo "no devices in state 'device' (check adb devices)"; exit 1; }
fail=0
# Install and wipe every device before launching any, or a not-yet-wiped node syncs its old feed into a wiped one.
for s in $SERIALS; do
    adb -s "$s" install -r "$APK" >/dev/null || { echo "$s: install failed"; fail=1; continue; }
    if [ $FRESH = 1 ]; then adb -s "$s" shell pm clear $PKG >/dev/null || echo "$s: pm clear failed"; fi
done
for s in $SERIALS; do
    sdk=$(adb -s "$s" shell getprop ro.build.version.sdk | tr -d '\r')
    if [ "$sdk" -ge 31 ]; then perms="BLUETOOTH_SCAN BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT"; else perms="ACCESS_FINE_LOCATION"; fi
    if [ "$sdk" -ge 33 ]; then perms="$perms POST_NOTIFICATIONS"; fi
    for p in $perms; do adb -s "$s" shell pm grant $PKG android.permission.$p || echo "$s: grant $p failed"; done
    adb -s "$s" shell cmd bluetooth_manager enable >/dev/null 2>&1 || echo "$s: could not enable Bluetooth; turn it on by hand"
    adb -s "$s" shell am start -n $PKG/.MainActivity >/dev/null || { echo "$s: launch failed"; fail=1; continue; }
    echo "$s: installed, granted ($perms), launched"
done
exit $fail
