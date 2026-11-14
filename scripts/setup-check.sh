#!/usr/bin/env bash
# Checks the laptop has what the agents need. Prints OK/MISSING per item. Never changes anything.
ok(){ printf '  OK       %s\n' "$1"; }; bad(){ printf '  MISSING  %s\n           fix: %s\n' "$1" "$2"; FAIL=1; }
FAIL=0
echo "Tools"
command -v java >/dev/null && java -version 2>&1 | grep -Eq 'version "(1[7-9]|2[0-9])' && ok "JDK 17+" || bad "JDK 17 or newer" "sudo apt install -y openjdk-17-jdk (or use Android Studio's bundled JDK: export JAVA_HOME=<studio>/jbr)"
for t in git curl jq node; do command -v $t >/dev/null && ok "$t" || bad "$t" "Linux: sudo apt install -y $t   Windows: winget install (Git.Git | jqlang.jq | OpenJS.NodeJS.LTS)"; done
[ -n "${ANDROID_HOME:-$ANDROID_SDK_ROOT}" ] && [ -d "${ANDROID_HOME:-$ANDROID_SDK_ROOT}" ] && ok "ANDROID_HOME=${ANDROID_HOME:-$ANDROID_SDK_ROOT}" || bad "ANDROID_HOME" "Linux ~/.bashrc: export ANDROID_HOME=\$HOME/Android/Sdk   Windows ~/.bashrc in Git Bash: export ANDROID_HOME=\$LOCALAPPDATA/Android/Sdk"
command -v adb >/dev/null && ok "adb" || bad "adb on PATH" "export PATH=\$PATH:\$ANDROID_HOME/platform-tools"
command -v emulator >/dev/null && ok "emulator" || bad "emulator on PATH" "export PATH=\$PATH:\$ANDROID_HOME/emulator"
command -v sdkmanager >/dev/null && ok "sdkmanager/avdmanager" || bad "Android SDK command-line tools" "Android Studio > SDK Manager > SDK Tools > Android SDK Command-line Tools; then export PATH=\$PATH:\$ANDROID_HOME/cmdline-tools/latest/bin"
command -v claude >/dev/null && ok "claude" || bad "Claude Code CLI" "see https://code.claude.com/docs"
echo "Emulator acceleration"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    if command -v emulator >/dev/null && emulator -accel-check 2>&1 | grep -qiE "WHPX|AEHD|usable"; then ok "hypervisor (WHPX/AEHD)"
    else bad "emulator acceleration" "Windows: Turn Windows features on or off > tick Windows Hypervisor Platform > reboot; check with: emulator -accel-check"; fi ;;
  *)
    [ -e /dev/kvm ] && [ -w /dev/kvm ] && ok "/dev/kvm usable" || bad "KVM access" "sudo apt install -y qemu-kvm && sudo usermod -aG kvm \$USER, then log out and back in" ;;
esac
echo "Devices"
if command -v adb >/dev/null; then
  adb start-server >/dev/null 2>&1
  n=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l)
  adb devices -l | awk 'NR>1 && NF' | sed 's/^/  /'
  [ "$n" -ge 3 ] && ok "$n devices ready" || echo "  NOTE     $n device(s) ready; need 3 phones or emulators (see plan 10.3a, 10.4). 'unauthorized' = accept the prompt on the phone."
fi
command -v emulator >/dev/null && { echo "Virtual devices:"; emulator -list-avds | sed 's/^/  /'; }
exit $FAIL
