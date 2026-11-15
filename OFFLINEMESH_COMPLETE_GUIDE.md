# OfflineMesh: complete guide, start to finish

MadHacks Fall 2026. Everything in one file: what to download, how to set up the laptop and phones, every config file to create, how to run the coding agents, the full build specification, testing, and the demo.

**Deadline:** about Sun Nov 15, 11:00 AM CT (confirm on Devpost). **Feature freeze:** 8:00 AM.

## Contents

- **Part 1. Downloads checklist**
- **Part 2. Laptop, phones and repo setup** (Windows or Ubuntu)
- **Part 3. Files to create in the repo** (copy each block into the path shown)
- **Part 4. Running the agents and keeping them looping**
- **Part 5. The build specification** (the agents' source of truth)
- **Part 6. Timeline at a glance**

**For a coding agent reading this file:** Part 5 is your specification. Parts 1, 2 and 4 are for the human. If the files in Part 3 are missing from the repo, create them exactly as shown, then tell the human to restart the session so the settings and agents load.

---

# Part 1. Downloads checklist

| What | Windows | Ubuntu | Needed for |
|---|---|---|---|
| Git (Git Bash on Windows) | `winget install Git.Git` | `sudo apt install -y git` | Repo; Claude Code's bash shell on Windows |
| Claude Code | `irm https://claude.ai/install.ps1 \| iex` (PowerShell) | `curl -fsSL https://claude.ai/install.sh \| bash` | The coding agents |
| Android Studio (includes JDK, adb, emulator) | `winget install Google.AndroidStudio` | `sudo snap install android-studio --classic` | Building and running the app |
| Android SDK Command-line Tools | Android Studio → SDK Manager → SDK Tools | same | `sdkmanager`, `avdmanager` |
| An emulator image, API 34 or newer, x86_64 Google APIs | Android Studio → Device Manager (or the agent runs `sdkmanager`) | same | Three emulated phones |
| jq | `winget install jqlang.jq` | `sudo apt install -y jq` | The test hook |
| Node.js | `winget install OpenJS.NodeJS.LTS` | `sudo apt install -y nodejs` | The Ponytail plugin |
| JDK 17 (only if not using Android Studio's) | bundled with Android Studio | `sudo apt install -y openjdk-17-jdk` | Gradle builds |
| Phone USB drivers | Samsung Android USB Driver (samsung.com); OPPO usually via Windows Update | not needed (`sudo apt install -y android-sdk-platform-tools-common` for USB rules) | adb seeing the phones |
| Emulator acceleration | Windows Hypervisor Platform (Windows features, reboot) | `sudo apt install -y qemu-kvm` and `sudo usermod -aG kvm $USER` | Fast emulators |
| Ponytail plugin | inside Claude Code: `/plugin marketplace add DietrichGebert/ponytail`, then `/plugin install ponytail@ponytail` | same | Keeps agents from overbuilding |
| Graphify (optional, end only) | `pip install graphifyy && graphify install` | same | Architecture graph for the pitch |
| USB cables | 3 phone cables | | Phones to laptop |

---

# Part 2. Laptop, phones and repo setup

## 1. One-time laptop setup (about 20 minutes)

**Windows or Ubuntu both work.** Pick one and run everything there: agents, Android Studio, emulators and the phones. Windows is fine if you are already on it; it just needs Git for Windows (so Claude Code gets a bash shell and the scripts run) and USB drivers for the phones. Ubuntu needs no drivers. Do not use Docker or WSL for the Android parts: the emulator can't run inside Docker, and USB phones need extra passthrough into WSL.

### Option W: Windows (native, no WSL)

In PowerShell:

```powershell
winget install Git.Git jqlang.jq OpenJS.NodeJS.LTS Google.AndroidStudio
irm https://claude.ai/install.ps1 | iex
```

1. **Hypervisor for the emulator:** Start → "Turn Windows features on or off" → tick **Windows Hypervisor Platform** → OK → reboot. (Virtualization/SVM must be on in the BIOS; it usually is on a ROG.)
2. **Android Studio:** open it once and let it install the SDK. In SDK Manager → SDK Tools also tick **Android SDK Command-line Tools** and, for the Samsungs if Windows doesn't find a driver, install **Samsung's Android USB Driver for Windows** from samsung.com. OPPO usually installs through Windows Update; if `adb devices` shows nothing, install the OPPO/OnePlus USB driver.
3. **Work in Git Bash** (Start → Git Bash), not PowerShell, for every command below and for running `claude`. Add the SDK to your path once:
   ```bash
   cat >> ~/.bashrc <<'RC'
   export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
   export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin"
   RC
   source ~/.bashrc
   ```
4. When Windows Firewall asks about Java listening on the network, allow it on **Private** networks (needed for the Wi-Fi LAN fallback).
5. If the Stop hook errors in Claude Code on Windows, delete the `"hooks"` block from `.claude/settings.json`; `/goal` still keeps the agents looping.

### Option U: Ubuntu

```bash
sudo apt update && sudo apt install -y openjdk-17-jdk git curl jq qemu-kvm android-sdk-platform-tools-common
sudo usermod -aG kvm,plugdev $USER          # then log out and back in
sudo snap install android-studio --classic  # open it once; let it install the SDK
# In Android Studio > SDK Manager > SDK Tools, also tick "Android SDK Command-line Tools".
cat >> ~/.bashrc <<'RC'
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin
RC
source ~/.bashrc
```

`android-sdk-platform-tools-common` installs the USB rules so adb can see the phones without root.

## 1b. Plugins (once per laptop login, about 2 minutes)

**Ponytail: install it.** It makes the agents write the least code that does the job, which suits a 22-hour build and the plain UI.

1. It needs Node.js on the PATH: `sudo apt install -y nodejs`.
2. Open `claude` once in any folder and send these as **two separate prompts**:
   ```
   /plugin marketplace add DietrichGebert/ponytail
   ```
   ```
   /plugin install ponytail@ponytail
   ```
3. Start new sessions afterwards. It runs on its own in every session; `/ponytail` shows the mode.

Use exactly `DietrichGebert/ponytail`. Several copies and lookalikes under other names exist.

**Graphify: skip it during the build; optional at the end.** It indexes an existing codebase so an agent can find things without searching every file. Your repo starts empty and four agents change it constantly, so the index would always be out of date, and its project install adds a hook before every search. One good use near the end: run it once to get an architecture graph for the Devpost page or the pitch.

```bash
pip install graphifyy && graphify install     # the package name has two y's; the command is graphify
# then inside Claude Code, in the main folder:  /graphify .
```

The result is `graphify-out/graph.html`. Don't run `graphify claude install` or `--strict` in the agent clones; those make every agent use the graph.

## 2. Repo

```bash
git clone <your empty GitHub repo> offlinemesh && cd offlinemesh
# put OFFLINEMESH_COMPLETE_GUIDE.md here, then create every file from Part 3
# append the ignore list from the 'Ignore file' section to .gitignore
echo "# STATUS" > STATUS.md
git add -A && git commit -m "Plan and Claude Code setup" && git push
scripts/setup-check.sh       # every line should say OK
```

Then make one clone per agent next to it, so agents don't fight over the same build folder (in Git Bash on Windows):

```bash
cd .. && for a in core android ui qa; do git clone <repo> om-$a; done
```

## 3. Phones

Part 5, section 10.4, steps 1 and 2: USB debugging on, plug in, accept the prompt. `adb devices -l` shows all three.

### Ignore file

Append this to `.gitignore` in the repo:

````text
.gradle/
build/
*/build/
local.properties
.idea/
*.iml
shots/
.claude/settings.local.json
````


---

# Part 3. Files to create in the repo

Create each file at the path shown, with exactly this content. On Linux or Git Bash, make the two scripts executable afterwards: `chmod +x scripts/setup-check.sh scripts/hooks/stop-check.sh`.

### `CLAUDE.md`

````markdown
# OfflineMesh: project instructions for Claude Code

The full specification is Part 5 of `OFFLINEMESH_COMPLETE_GUIDE.md` at the repo root. Section numbers like "section 8" or "5.6" refer to Part 5. Read it before doing anything. It wins over anything you remember or assume.

## Rules
- Work only inside the paths your workstream owns (section 8 of the plan). If you are not running as a named agent, follow section 9 in order.
- Section 6 (interfaces) is frozen. Changing it means editing the plan in the same commit and adding a line to `STATUS.md`.
- Small commits, often. Before every push: `git pull --rebase`. Stage only your own paths (`git add core/`, not `git add -A`). Never force-push.
- After each finished task or blocker, append one line to `STATUS.md`: `[agent] [HH:MM] done|blocked: ...`. Read `STATUS.md` when you start and after every pull.
- Do not add features outside section 2 "Build" unless the human asks. Plain UI only (section 7).
- Never run `sudo`. If something needs root, a cable, a tap on a phone, or a login, stop and tell the human exactly what to do.

## If the Ponytail plugin is active
Keep its "write less" rules for how you build things, but every item in the plan's section 2 "Build" list, the security checks in 5.6 and the tests in 10.1 and 10.2 are required, not optional. Do not cut them to save code.

## Commands you can use
- Build and test: `./gradlew :core:test`, `./gradlew :app:assembleDebug`, `./gradlew build`
- Phones and emulators: `adb devices -l`, `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`, `adb -s <serial> logcat -d -s OfflineMesh`, `adb -s <serial> forward tcp:8081 tcp:8080`
- Turn Bluetooth on without touching the phone: `adb -s <serial> shell cmd bluetooth_manager enable` (if it fails, ask the human)
- See a phone's screen: `adb -s <serial> exec-out screencap -p > shots/<name>.png`, then read the PNG to check the UI
- Tap or type on a phone: `adb -s <serial> shell input tap X Y`, `adb -s <serial> shell input text 'hello'`
- Emulators: `emulator -list-avds`, `emulator -avd meshA -no-snapshot-save &`
- Drive a node: `curl -s localhost:8081/api/snapshot`, `curl -s -X POST localhost:8081/api/cmd -d '{"t":"post","text":"hi"}'`
- Environment check: `scripts/setup-check.sh`

## Phones
OPPO Find X9 Pro = A, Galaxy Z Fold 7 = B (relay, hotspot host), Galaxy A52s = C, iPhone 18 Pro Max = browser guest only. Identify them with `adb devices -l` (model names appear). Record the serial-to-role map in `STATUS.md` the first time.

## When you are stuck
Same error three times in a row, or a time budget in section 9 exceeded by half: stop, write the blocker to `STATUS.md`, and tell the human. Do not loop on a failing approach.
````

### `.claude/settings.json`

````json
{
  "$schema": "https://json.schemastore.org/claude-code-settings.json",
  "permissions": {
    "allow": [
      "Edit",
      "Write",
      "Bash(./gradlew *)",
      "Bash(gradle *)",
      "Bash(java *)",
      "Bash(adb *)",
      "Bash(emulator *)",
      "Bash(avdmanager *)",
      "Bash(sdkmanager *)",
      "Bash(scripts/*)",
      "Bash(./scripts/*)",
      "Bash(bash scripts/*)",
      "Bash(git add *)",
      "Bash(git commit *)",
      "Bash(git pull *)",
      "Bash(git push)",
      "Bash(git push origin *)",
      "Bash(git status *)",
      "Bash(git diff *)",
      "Bash(git log *)",
      "Bash(git fetch *)",
      "Bash(git stash *)",
      "Bash(git rebase *)",
      "Bash(git checkout *)",
      "Bash(git switch *)",
      "Bash(git restore *)",
      "Bash(curl *)",
      "Bash(jq *)",
      "Bash(python3 *)",
      "Bash(mkdir *)",
      "Bash(cp *)",
      "Bash(mv *)",
      "Bash(touch *)",
      "Bash(chmod +x *)",
      "Bash(sleep *)",
      "Bash(kill *)",
      "Bash(pkill *)",
      "Bash(ss *)",
      "Bash(lsof *)",
      "Bash(unzip *)",
      "Bash(tee *)",
      "Bash(sort *)",
      "Bash(sed *)",
      "Bash(awk *)",
      "Bash(xargs *)",
      "Bash(graphify *)",
      "WebSearch",
      "WebFetch(domain:developer.android.com)",
      "WebFetch(domain:source.android.com)",
      "WebFetch(domain:kotlinlang.org)",
      "WebFetch(domain:ktor.io)",
      "WebFetch(domain:docs.gradle.org)",
      "WebFetch(domain:github.com)",
      "WebFetch(domain:stackoverflow.com)"
    ],
    "deny": [
      "Bash(sudo *)",
      "Bash(git push --force *)",
      "Bash(git push -f *)",
      "Bash(git reset --hard *)",
      "Bash(rm -rf / *)",
      "Bash(rm -rf ~ *)",
      "Bash(rm -rf ~/*)",
      "Bash(adb * shell rm *)",
      "Bash(adb shell rm *)",
      "Bash(adb * reboot *)",
      "Read(./.env)",
      "Read(./.env.*)",
      "Read(~/.ssh/**)"
    ]
  },
  "hooks": {
    "Stop": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/scripts/hooks/stop-check.sh",
            "timeout": 900
          }
        ]
      }
    ]
  }
}
````

### `scripts/hooks/stop-check.sh`

````bash
#!/usr/bin/env bash
# Stop hook: before an agent ends its turn, if it changed JVM code, the core tests must pass.
# Exit 2 sends the failure back to the agent so it keeps fixing. Lets it stop on the second try
# (stop_hook_active) so it can never loop forever.
INPUT=$(cat)
if [ "$(printf '%s' "$INPUT" | jq -r '.stop_hook_active // false' 2>/dev/null)" = "true" ]; then exit 0; fi
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
[ -x ./gradlew ] || exit 0
[ -d core ] || exit 0
# Only check when this clone has uncommitted or unpushed changes to JVM modules.
changed=$(git status --porcelain -- core lan uiserver desktop 2>/dev/null)
unpushed=$(git log --oneline @{u}..HEAD -- core lan uiserver desktop 2>/dev/null)
[ -z "$changed$unpushed" ] && exit 0
if ! out=$(timeout 600 ./gradlew -q :core:test 2>&1); then
  echo "Core tests or compile failed. Fix this before stopping (or record a blocker in STATUS.md):" >&2
  printf '%s\n' "$out" | tail -n 40 >&2
  exit 2
fi
exit 0
````

### `scripts/setup-check.sh`

````bash
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
````

### `.claude/agents/mesh-core.md`

````markdown
---
name: mesh-core
description: Agent A. Builds the protocol core (identity, codec, crypto, PoW, security pipeline, routing, sync, store) and the virtual-time simulator tests in core/. Use for anything under core/.
model: inherit
---
You are Agent A from OFFLINEMESH_COMPLETE_GUIDE.md section 8. You own `core/` only (and create the Gradle root plus empty module shells first).

Order: (1) within 45 minutes push compiling stubs of section 6 so other agents can build; (2) implement sections 4 and 5.1 to 5.12; (3) SimTransport/SimNetwork with virtual time and every test in 10.1 and 10.2.

Loop: write code, run `./gradlew :core:test`, read the failures, fix, repeat. Commit and push after each test group goes green. Print the measured broadcast transmission count in test 9. `core/` must stay free of `android.*` and of APIs newer than Android API 29.

Done when `./gradlew :core:test` exits 0 with all 10.1 and 10.2 tests present and the counts printed, and everything is pushed.
````

### `.claude/agents/mesh-android.md`

````markdown
---
name: mesh-android
description: Agent B. Builds the Android app (BLE L2CAP transport, foreground service, WebView shell, permissions) in app/ and the deploy/forward scripts, and tests on emulators and the three real phones. Use for anything under app/.
model: inherit
---
You are Agent B from OFFLINEMESH_COMPLETE_GUIDE.md section 8. You own `app/`, `scripts/deploy-all.sh`, `scripts/forward-ui.sh`.

Start with the 2-hour Bluetooth spike: first on two emulators (plan 10.3a; create AVDs with avdmanager if missing), then on the real phones. Write the result to STATUS.md before anything else. If it has not worked on real phones by 4 hours, switch phones to LanTransport (plan Gate 1) and tell the human.

Loop: build with `./gradlew :app:assembleDebug`, install on every device, read `adb logcat -d -s OfflineMesh`, take a screenshot with `adb exec-out screencap -p` and look at it, fix, repeat. Use `adb shell pm grant` for permissions and `adb shell cmd bluetooth_manager enable` for Bluetooth. When a physical action is unavoidable (USB debugging prompt, a system dialog pm grant cannot clear, OEM battery settings in plan 10.4), stop and tell the human exactly what to tap on which phone.

Done when `scripts/e2e.sh 8081 8082 8083` passes against the three real phones (plan section 1 checks 1 to 6). Then build guest access (5.13) together with mesh-ui.
````

### `.claude/agents/mesh-ui.md`

````markdown
---
name: mesh-ui
description: Agent C. Builds the plain web UI and its local server (uiserver/), the LAN transport (lan/), the desktop node (desktop/), run-3-desktop.sh and e2e.sh. Use for anything under uiserver/, lan/ or desktop/.
model: inherit
---
You are Agent C from OFFLINEMESH_COMPLETE_GUIDE.md section 8. You own `uiserver/`, `lan/`, `desktop/`, `scripts/run-3-desktop.sh`, `scripts/e2e.sh`.

Start the UI against a fake MeshNode that emits sample events, so you do not wait for core. Keep the page exactly as plain as section 7 says: no styling beyond it.

Loop: run `scripts/run-3-desktop.sh`, then `scripts/e2e.sh 8081 8082 8083`, read what failed, fix, repeat. Use `curl` against /api/snapshot and /api/cmd to inspect nodes. Kill leftover node processes before each run.

Done when run-3-desktop.sh plus e2e.sh exit 0 against the real core (Gate 2), and everything is pushed. Then guest.html and the 8090 server for 5.13.
````

### `.claude/agents/mesh-qa.md`

````markdown
---
name: mesh-qa
description: Agent D. Writes extra adversarial tests, THREATS.md, DEMO.md and the Devpost text, and collects measured numbers. Use for docs/ and core/src/test/kotlin/mesh/redteam/.
model: inherit
---
You are Agent D from OFFLINEMESH_COMPLETE_GUIDE.md section 8. You own `docs/` and `core/src/test/kotlin/mesh/redteam/` only.

Write docs/THREATS.md from plan sections 11 and 12 and docs/DEMO.md from section 11 in plain words. Once the simulator exists, add adversarial tests that are not in 10.2 (oversized frames, truncated envelopes, ACK forgery, PRESENCE spam, slot reuse across restarts). Report any bug you find to STATUS.md for mesh-core; do not fix core code yourself.

Collect: broadcast transmissions per link count from the simulator, PoW mining time on each phone (ask mesh-android or read logcat), and the e2e results. Put them in docs/NUMBERS.md and the Devpost draft in docs/DEVPOST.md.

Done when all four docs exist, your tests run green with `./gradlew :core:test`, and everything is pushed.
````

---

# Part 4. Running the agents and keeping them looping

## 4. Start the agents (4 terminals)

In each clone, start Claude Code with the matching agent, then set its goal.

**Permission mode.** Start with `--permission-mode auto`. It approves safe actions without asking you, and the allow list in `.claude/settings.json` covers the build, adb and git commands. If Claude Code says auto mode isn't available on your plan, use `--permission-mode acceptEdits` instead; you will then get a prompt only for commands outside the allow list. Don't use `--dangerously-skip-permissions` on your own laptop.

The first time in each folder, Claude Code asks you to trust it. Say yes, or the allow rules and the Stop hook won't apply.

| Terminal | Folder | Start | Then type |
|---|---|---|---|
| 1 | `om-core` | `claude --agent mesh-core --permission-mode auto` | `/goal ./gradlew :core:test exits 0 with every test from OFFLINEMESH_COMPLETE_GUIDE.md 10.1 and 10.2 present and the transmission counts printed, and all work is committed and pushed. Stop after 80 turns or if blocked, writing the blocker to STATUS.md.` |
| 2 | `om-ui` | `claude --agent mesh-ui --permission-mode auto` | `/goal scripts/run-3-desktop.sh followed by scripts/e2e.sh 8081 8082 8083 exits 0 against the real core, and all work is pushed. Until core is ready, build against the fake node and keep pulling. Stop after 80 turns or if blocked.` |
| 3 | `om-android` | `claude --agent mesh-android --permission-mode auto` | `/goal the Bluetooth spike result is in STATUS.md, then scripts/e2e.sh 8081 8082 8083 exits 0 against the three real phones with all six checks from section 1 passing, and all work is pushed. Stop and ask me whenever a phone needs a physical action. Stop after 120 turns or if blocked.` |
| 4 | `om-qa` | `claude --agent mesh-qa --permission-mode auto` | `/goal docs/THREATS.md, docs/DEMO.md, docs/NUMBERS.md and docs/DEVPOST.md exist and match the plan, the redteam tests run green under ./gradlew :core:test, and all work is pushed. Stop after 60 turns.` |

Start terminal 1 first. The others can start right away; they pull core when it appears.

## What keeps them looping

- **`/goal`** keeps each agent working turn after turn until its condition is met, and a separate model checks the condition after every turn. `/goal` with no text shows progress; `/goal clear` stops it.
- **The Stop hook** (`scripts/hooks/stop-check.sh`) runs the core tests whenever an agent tries to stop with changed JVM code. If they fail, the agent is sent back to fix them. It lets the agent stop on the second try, so it can't loop forever.
- **The allow list** lets builds, tests, adb, the emulator, curl and git run without prompts. `sudo`, force-push, `reset --hard` and deleting files on phones are blocked.

## What the agents can and can't do on the phones

They can install, grant permissions, turn Bluetooth on, read logs, take screenshots and look at them, tap and type, and forward ports, all through `adb`. They can't accept the USB debugging prompt, change OEM battery settings, or clear some system dialogs. When they need that, they stop and tell you which phone and what to tap. Keep the phones plugged in and unlocked.

## Watching progress

`git pull && tail STATUS.md` in the main folder. Open `localhost:8081` to `8083` in a browser once forwarding is set up.

## Optional: one lead with teammates

Instead of four terminals, Claude Code's experimental agent teams can run the same four roles from one session with a shared task list. Add `"env": {"CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS": "1"}` to `.claude/settings.json`, start one `claude`, and ask: "Spawn four teammates using the mesh-core, mesh-ui, mesh-android and mesh-qa agent types and follow OFFLINEMESH_COMPLETE_GUIDE.md." All teammates then share one folder and their permission prompts come to you in the lead. It's experimental and can't be resumed after a crash, so the four-terminal setup is the safer choice for a 22-hour deadline.


---

# Part 5. The build specification

MadHacks Fall 2026. Offline phone-to-phone messaging that relays A → B → C with no internet, aimed at the TDS networking challenge and the Privacy and Security challenge.

**This Part 5 is the single source of truth for what to build.** It replaces the three earlier documents (layer design, `CryptoSecurityEngine.kt` write-up, tactical web console). Where they disagree with this file, this file wins. Appendix A lists what changed and why.

---

### 0. How to use this file

- **One agent:** do Phases 1 to 6 in section 9 in order.
- **Several agents:** each takes one workstream in section 8 and touches only the paths it owns.
- **Interfaces in section 6 are frozen.** If you must change one, edit section 6 in this file in the same commit and tell the human.
- **Stop and ask the human** when a gate in section 9 fails, when a milestone runs 50% over its time budget, or when you need a physical phone action (unlock, permission dialog, Bluetooth toggle).
- **Do not add scope.** Anything not in section 2 "Build" is out unless the human says otherwise.
- **This is a specification, not code.** You write all of it. The earlier `CryptoSecurityEngine.kt` and `MainActivity.kt` snippets were incomplete (one stops mid-function) and conflict with this file in several places; reuse a function from them only after checking it against sections 4 to 6.
- **Status log:** append one line per finished task or blocker to `STATUS.md` at the repo root (`[agent] [time] done/blocked: ...`).

#### Human fills these in before handing the file over

| Item | Value |
|---|---|
| TDS challenge wording | Not published on Devpost as of Sat 1:15 PM. Working assumption: the four questions in section 11 (spam, malicious joiners, unbounded spread, privacy and integrity across relays). If the official text appears, paste it here and tell every agent. |
| Submission deadline | About **Sun Nov 15, 11:00 AM CT** (22 hours from Sat 1:13 PM). Confirm on Devpost. **Feature freeze 8:00 AM.** |
| Phones | Android: OPPO Find X9 Pro (A), Samsung Galaxy Z Fold 7 (B, relay and hotspot host), Samsung Galaxy A52s (C). iPhone 18 Pro Max: browser-only guest, see 10.4 and stretch item 1. |
| Coding agents | 4, one per workstream in section 8. Kickoff prompts in Appendix B. |

---

### 1. Goal and acceptance test

Three Android phones with mobile data and Wi-Fi internet off.

**Done means all of these pass on real phones:**

1. Phone A sends a direct message to phone C. A and C have no direct link (blocked in software, see 5.9). The message arrives at C through B, and A's screen shows "Delivered, 2 hops via B".
2. B cannot read that message. B's screen never shows it, and B's log shows only ciphertext being forwarded.
3. A public post from A appears on B and C once each.
4. With B set to "tamper" mode, C rejects the altered message and shows a security log line. A's key is not banned.
5. A flood attempt from A (4 posts inside one 10-second window) gets A's key banned on B for 10 minutes, with a log line. B relays nothing more from A, so C holds exactly the first three posts.
6. A posts while C is disconnected. When C reconnects, the post appears on C within 20 seconds.

**Fallback demo (must exist by Gate 2):** the same six checks pass with three desktop processes on one laptop over TCP. If phone Bluetooth fails on demo day, this is what gets shown.

---

### 2. Scope

#### Build (in this order)

| # | Piece | Where |
|---|---|---|
| 1 | Protocol core: identity, envelope codec, crypto, proof-of-work, ingress checks, routing, sync, store | `core/` |
| 2 | In-memory network simulator and tests that run the real core | `core/src/test` |
| 3 | TCP/LAN transport and desktop node | `lan/`, `desktop/` |
| 4 | Local web UI and its server (plain, see section 7) | `uiserver/` |
| 5 | Android app: BLE L2CAP transport, foreground service, WebView shell | `app/` |
| 6 | Red-team buttons (tamper, flood, spoof, replay) and security log | `core/`, `uiserver/` |
| 7 | Message log on disk so history survives restart | `core/` |
| 8 | Owner additions: any number of phones, group chats, nicer UI (section 13) | `core/`, `uiserver/`, `app/`, `scripts/` |

#### Stretch (only after Gate 3, in this order)

1. **Guest access for phones without the app (the iPhone).** Spec in 5.13. This goes first because it lets anyone in the room join with no install, which is the strongest point for a networking judge.
2. Photo in a DM: downscale to a JPEG of 40 KB or less and send it in one frame. No chunking.
3. Android notification for incoming DMs, with inline reply.
4. Ban proof gossip: flood the two conflicting signed headers so nodes that did not witness the flood also ban the key. Without this, only nodes that see both conflicting posts ban the sender; nodes behind them are protected because the banning node stops relaying.
5. QR code for identity verification.

#### Do not build

- Captive portal pop-up on a phone hotspot. A stock Android phone cannot hijack hotspot DNS or bind port 80 without root.
- Wi-Fi Aware, LocalOnlyHotspot bursts, Dandelion++, fountain codes, zero-knowledge rate limiting, Double Ratchet, `/dev/tun` VPN layer, video, group certificates, Android Bubbles.
- Room/SQLite. Use the append-only file in 5.11.
- Any MCP server. Use `adb`, `./gradlew` and `curl` from the shell (section 10).
- The Python mesh simulator from the earlier document. It models a toy, not this code.
- An iOS app.

---

### 3. Architecture

```
            ┌────────────────────────── one process per node ──────────────────────────┐
 browser /  │  uiserver (Ktor, 127.0.0.1:8080)                                          │
 WebView ◄──┼─► HTTP + WebSocket, JSON  ◄──►  MeshNode (core)                           │
            │                                   │  single-threaded event loop           │
            │                                   ├─ Security (ingress checks, bans)      │
            │                                   ├─ Router (dedup, flood, reverse path)  │
            │                                   ├─ SyncEngine (ID-list anti-entropy)    │
            │                                   ├─ Store (memory + append-only file)    │
            │                                   └─ Transport[]  (frozen interface)      │
            └───────────────────────────────────────┬──────────────┬───────────────────┘
                                                    │              │
                                         BleL2capTransport     LanTransport
                                         (Android only)        (TCP + UDP discovery;
                                                                desktop and Android)
```

- **`core/` is pure Kotlin/JVM with no Android imports.** The same code runs in unit tests, in the desktop node and in the Android app. This is what makes the simulator meaningful.
- **Transports only move frames.** They know nothing about envelopes, keys or routing.
- **All node state is touched from one thread** (the mesh loop). Transports, timers and the UI server post work onto it. Proof-of-work mining runs on a worker thread and posts its result back.
- **The UI is a web page served on localhost.** On the phone it is shown in a WebView. On a laptop it is opened in a browser. `adb forward` lets a laptop show and script each phone's UI.

#### Repository layout

```
offlinemesh/
  settings.gradle.kts  build.gradle.kts  gradle/libs.versions.toml
  OFFLINEMESH_COMPLETE_GUIDE.md   STATUS.md
  core/        kotlin("jvm")   package mesh.core
  lan/         kotlin("jvm")   package mesh.lan     (LanTransport; used by desktop and app)
  uiserver/    kotlin("jvm")   package mesh.ui      (+ resources/web/index.html)
  desktop/     kotlin("jvm") + application   package mesh.desktop
  app/         Android application   package com.meshnet.offline
  scripts/     deploy-all.sh  forward-ui.sh  run-3-desktop.sh  e2e.sh
  docs/        DEMO.md  THREATS.md
```

#### Build settings

- JDK 17. `minSdk 29`, `targetSdk 34`. Use the Android Gradle Plugin and Kotlin versions that the installed Android Studio's new-project template generates; do not hand-pick older ones.
- `core/` may only use APIs that exist on Android API 29. **Not allowed:** `java.util.HexFormat`, `java.net.http`, virtual threads, `android.*`. Use `java.util.Base64`, `java.security`, `javax.crypto`, `java.nio`, `java.util.concurrent`.
- UI server: Ktor CIO server with the WebSockets plugin, latest stable version that matches the Kotlin version. If Ktor dependency or packaging problems cost more than 30 minutes, switch to NanoHTTPD 2.3.1 with `nanohttpd-websocket` and keep the same JSON protocol.
- Android packaging: if the build fails on duplicate `META-INF` files, exclude `META-INF/INDEX.LIST` and `META-INF/*.kotlin_module` in `packaging.resources`.
- JSON for the UI protocol: `kotlinx-serialization-json`.

---

### 4. Constants

| Name | Value |
|---|---|
| `PROTOCOL_VERSION` | 1 |
| `MAX_FRAME` | 60,000 bytes |
| `MAX_POST_BODY` | 1,000 bytes |
| `MAX_DM_PLAINTEXT` | 40,000 bytes |
| `MAX_NAME` | 32 bytes |
| Initial TTL | POST 6, PRESENCE 6, DM 8, ACK 8 |
| `EPOCH_SECONDS` | 10 |
| `SLOTS_PER_EPOCH` | 3 (POST only) |
| `MAX_AGE_EPOCHS` | 360 (one hour) |
| `MAX_FUTURE_EPOCHS` | 2 |
| PoW bits (POST, DM) | `14 + min(6, bodyLen / 2048) + min(4, initTtl / 4)` |
| Forward jitter | random 30 to 100 ms |
| `PRESENCE_INTERVAL` | 30 s |
| `ROUTE_TTL` | 120 s |
| `SYNC_INTERVAL` | 15 s per link, plus once at link-up |
| `RELAY_STORE_CAP` | 256 envelopes, oldest dropped first |
| `SEEN_CAP` | 4,096 message IDs, least recently used dropped |
| `ACK_TIMEOUT` | 5 s (UI status only) |
| Link token bucket | 30 envelopes per 10 s |
| Link score | starts at 100; malformed −20, bad PoW −50, bad signature −100, over rate −10; below 0 closes the link and refuses that peer for 60 s |
| `BAN_DURATION` | 10 minutes |
| `MAX_LINKS` | 5 |
| `HELLO_TIMEOUT` | 5 s |

---

### 5. Protocol specification (version 1)

All integers are big-endian. `||` means concatenation.

#### 5.1 Identity

- One EC P-256 (`secp256r1`) key pair per node, generated once and **saved to disk** (`identity.bin`: PKCS#8 private key and X.509 public key). The same key signs (ECDSA) and does key agreement (ECDH). This is a hackathon shortcut; list it in `docs/THREATS.md`.
- Public key on the wire: X.509 SubjectPublicKeyInfo DER (`publicKey.encoded`, 91 bytes for P-256).
- `NodeId` = first **8 bytes** of `SHA-256(publicKeyDER)`. Shown to people as 16 hex characters in groups of four.
- Display name is self-chosen, 32 bytes or less, carried in PRESENCE. Names are not trusted; the NodeId is.
- A contact is **verified** when the user compares the 16-hex fingerprint on both screens and taps "Verified".

#### 5.2 Frames (every transport)

```
u32  length      counts everything after this field; valid range 1..60000
u8   frameType
...  payload
```

| frameType | Name | Payload |
|---|---|---|
| `0x01` | ENVELOPE | one envelope (5.3) |
| `0x10` | HELLO | `u8 pubLen` `pub` `16-byte nonce` |
| `0x11` | HELLO_AUTH | `u8 sigLen` `sig` |
| `0x12` | SYNC_OFFER | `u16 count` then `count` × 8-byte msgId |
| `0x13` | SYNC_WANT | `u16 count` then `count` × 8-byte msgId |
| `0x14` | BAN_PROOF | stretch 4: `u16 len` POST envelope, `u16 len` POST envelope (5.3, trailers ignored) |

A length outside 1..60000 closes the link at once. Never allocate from an unchecked length.

#### 5.3 Envelope

```
--- core: signed by the origin, never changes in transit ---
u8    version        = 1
u8    type           1 PRESENCE, 2 POST, 3 DM, 4 ACK
u8    srcPubLen
...   srcPub
8B    dst            NodeId, or 8 zero bytes for broadcast
u64   lamport
u32   epoch          floor(unixSeconds / 10)
u8    slot           POST: 0..2    other types: 0
u8    initTtl
u16   bodyLen
...   body
--- trailer: not signed ---
u64   powNonce       0 for PRESENCE and ACK
u8    sigLen
...   sig            ECDSA with SHA-256 over core, DER encoded
u8    ttl            hops remaining
u8    hops           hops taken so far
```

Derived by every receiver, never transmitted:

- `srcId = SHA-256(srcPub)[0..8)`
- `msgId = SHA-256(core)[0..8)`

Because the ID is a hash of the content, changing any signed field produces a different message with an invalid signature.

**Bodies**

| Type | Body |
|---|---|
| PRESENCE | `u8 nameLen` `name` (UTF-8) |
| POST | UTF-8 text, 1,000 bytes or less |
| DM | `12-byte IV` `AES-256-GCM ciphertext` `16-byte tag`; plaintext is `u8 kind` (0 text, 1 JPEG) then data |
| ACK | 8-byte msgId of the DM being acknowledged |

**Proof of work (POST and DM):** `SHA-256(core || powNonce)` must start with the number of zero bits given in section 4. Mining happens off the mesh loop.

**TTL:** the origin sets `ttl = initTtl`, `hops = 0`. A relay that forwards sets `ttl − 1`, `hops + 1`, and does not forward when `ttl` would reach 0. Receivers drop envelopes where `ttl + hops != initTtl`. This is a sanity check, not a security guarantee (see 12).

#### 5.4 DM encryption

- `shared = ECDH(myPrivate, peerPublic)`
- `key = SHA-256(shared || "offlinemesh-dm-v1")`
- `AES/GCM/NoPadding`, fresh random 12-byte IV per message, 128-bit tag, additional authenticated data = `srcId || dstId`.
- Relays see only ciphertext. There is no forward secrecy; list it in `docs/THREATS.md`.

#### 5.5 Link handshake

1. On link-up both sides send HELLO with their public key and a random 16-byte nonce.
2. On receiving HELLO, reply HELLO_AUTH with `sig = ECDSA(myPrivate, "offlinemesh-hello-v1" || peerNonce || myNodeId || peerNodeId)`.
3. On receiving a valid HELLO_AUTH the link is **authenticated** and bound to that neighbour's NodeId.
4. Before authentication, ignore every other frame. No valid HELLO_AUTH within `HELLO_TIMEOUT` closes the link.
5. If an authenticated link to that neighbour already exists, close the newer one. A link to yourself is closed.
6. Right after authentication: send your own PRESENCE, send the latest cached PRESENCE of every known contact (64 at most), then send SYNC_OFFER.

#### 5.6 Ingress pipeline

Run in this order for every ENVELOPE frame. Cheap checks first.

| # | Check | On failure |
|---|---|---|
| 0 | Link is authenticated and not muted | ignore |
| 1 | Parses exactly, lengths within limits for its type | drop, link score −20, log `MALFORMED` |
| 2 | `msgId` already in the seen set | note that this link has it, drop silently |
| 3 | `srcId` is banned | drop silently |
| 4 | Version is 1, `ttl + hops == initTtl`, `initTtl ≤ 8`, `lamport ≤ localLamport + 1,000,000` | drop, link score −20 |
| 5 | Proof of work valid (POST, DM) | drop, link score −50, log `BAD_POW` |
| 6 | ECDSA signature valid | drop, link score −100, log `BAD_SIGNATURE` |
| 7 | `now − 360 ≤ epoch ≤ now + 2` (in epochs) | drop, log `STALE` |
| 8 | Type rules (below) | as stated |
| 9 | Link token bucket has a token (envelopes the node asked for with SYNC_WANT are exempt) | drop, link score −10, log `LINK_FLOOD` |
| ✓ | Accept: add to seen set, update Lamport clock, learn route, store, deliver or forward | |

**Rule that must not be broken: failures in steps 1 to 6 penalise the link that delivered the envelope, never the claimed source.** Anyone can put someone else's public key in a forged envelope. An honest node validates before forwarding, so the neighbour that hands over an invalid envelope is the one at fault. A source key is banned only on cryptographic proof (step 8, POST).

**Type rules (step 8)**

- **POST:** `slot` must be 0..2. Keep a ledger `(srcId, epoch, slot) → msgId`. A second, different `msgId` for the same key is equivocation: two valid signatures by the same key for the same slot. Ban `srcId` for `BAN_DURATION`, log `RATE_EQUIVOCATION`, drop. Evict ledger entries older than `MAX_AGE_EPOCHS`. Stretch 4: after `JITTER_MAX` the banning node sends BAN_PROOF (the stored first POST and the second) to every live link except the one it came on and the offender. A receiver takes a link token (else `LINK_FLOOD`), ignores proofs for keys it already bans (this is the dedupe), then checks both are POSTs with the same `srcPub`, `epoch` and `slot` (0..2), different `msgId`s and valid signatures; any failure is link score −100 and `BAD_SIGNATURE`, banning nobody. A valid, fresh proof bans the key, logs `RATE_EQUIVOCATION` and is relayed the same way.
- **PRESENCE:** accept at most one per `srcId` per 10 s of local time; drop the rest silently. Update the contact table (name, public key, last seen) only if its `lamport` is higher than the cached one. Keep the latest PRESENCE envelope per contact for the link-up replay in 5.5. Not stored in the message store.
- **DM:** no slot rule.
- **ACK:** accept only if the acknowledged `msgId` is a DM in the seen set, that DM's `dst` equals this ACK's `srcId`, and no ACK for that DM was accepted before. Otherwise drop silently. This allows exactly one ACK per real DM.

**Own sending limits:** a node never sends more than 3 POSTs per epoch (the UI shows "slow down"). After start-up, wait until the next epoch begins before the first POST, so a restart cannot reuse a slot and get the node banned.

#### 5.7 Routing

- **Seen set:** an LRU of `msgId`s with, per ID: the link it first arrived on, the set of links known to have it, and for DMs the `dst` and an "acked" flag.
- **Route learning:** every accepted envelope from `srcId` that arrived over link `L` with `hops = h` sets `route[srcId] = (L, h, now)` if there is no route, the old one expired, or `h` is not worse. PRESENCE floods give every node a route to every other node.
- **Broadcast (dst all zero):** deliver locally. Then, after one random jitter delay, forward to every authenticated link except links known to have it. An envelope that arrives again during the jitter marks that link as having it.
- **DM for me:** decrypt, deliver, send an ACK on the link the DM arrived on.
- **DM for someone else:** if `route[dst]` is fresh and its link is up, forward on that link only. Otherwise forward like a broadcast.
- **ACK for someone else:** forward it on the link the acknowledged DM first arrived on, so the ACK walks the DM's path backwards. If that link is down, drop the ACK (the sender's status stays `SENT`; the message itself was delivered).
- **Cost bound to state in the pitch:** each node forwards a given message at most once per link and never back to a neighbour that has it, so one broadcast costs at most `2 × (number of links)` transmissions. The simulator test in 10.2 must print the measured number.

Trickle-style "stay silent if two neighbours already relayed it" is **not** used. BLE L2CAP links are point-to-point, so a node cannot overhear its neighbours, and suppressing a forward can strand a node whose only neighbour is the one that stayed silent.

#### 5.8 Sync (anti-entropy)

- At link-up and every `SYNC_INTERVAL`, send SYNC_OFFER listing the `msgId`s of stored POST and DM envelopes that are not expired and have `ttl > 1` (newest first, 512 at most).
- The receiver answers SYNC_WANT with the IDs it has not seen. The offerer sends those envelopes as normal ENVELOPE frames with `ttl − 1`, `hops + 1`.
- Synced envelopes go through the full ingress pipeline and are then delivered or forwarded like any new envelope.
- A relay deletes a stored DM when it sees the matching ACK.
- This is also the recovery path for DMs: a DM that got no ACK stays in the sender's store and spreads at the next sync.

The exact ID list replaces the 64-byte Bloom filter from the earlier design. At 256 stored messages that filter gives about 47% false positives, and a false positive means a missing message is never sent. 256 IDs are 2 KB, which is nothing on these links.

#### 5.9 Demo isolation (muting)

`MeshNode.setMuted(nodeId, true)` keeps the link open but ignores every frame from that neighbour and sends it nothing. On a desk where all three phones are in range, A mutes C and C mutes A to force the path through B. The UI shows muted peers plainly. Tell judges this is done in software.

#### 5.10 Message status

Own DM: `QUEUED` (no link yet) → `SENT` (handed to at least one link) → `DELIVERED` (valid ACK from the recipient; record the ACK's `hops` and the neighbour it came through). Received messages are `RECEIVED` with `hops` and `via`.

#### 5.11 Store

- In memory: feed (POSTs), DMs per peer, relay copies, contacts, routes, ledger, bans.
- On disk: `messages.log`, an append-only sequence of `u32 length || envelope || u64 receivedAtMs`, replayed at start-up through a trusted path that skips rate and freshness checks. Own and received messages are kept (cap 2,000). Relay-only copies follow `RELAY_STORE_CAP` and expire after one hour.
- Ordering in the UI: by `(lamport, msgId)`.

#### 5.12 Red-team hooks (debug only, visible in the UI)

| Hook | Behaviour |
|---|---|
| `TAMPER_RELAY` | when forwarding, flip one bit in the body |
| `DROP_RELAY` | forward nothing (black hole) |
| attack `flood` | bypass the own-sending limit and send 4 POSTs in one epoch, the fourth reusing slot 0 |
| attack `spoof` | send a POST carrying another contact's public key with a random signature |
| attack `replay` | resend the most recent stored envelope unchanged |

#### 5.13 Guest access (stretch item 1)

Lets a phone with no app (the iPhone, a judge's phone) use the mesh through one host phone's Wi-Fi hotspot and its browser.

- **Host:** phone B turns on its own hotspot (mobile data off) and taps "Guest mode on" in the native settings row. The app then starts a second, separate server on `0.0.0.0:8090`. The main UI server stays on 127.0.0.1:8080. Guest mode off stops the 8090 server.
- **Joining:** the host's Network tab shows a QR code of `http://<hotspot-ip>:8090/?pin=NNNN` and the PIN as text. Hotspot IP: the first IPv4 address on an interface whose name starts with `ap`, `swlan` or `wlan` other than the station interface; show all candidates if unsure. The guest scans it with the camera.
- **Guest page:** `guest.html`, same plain style. Shows the public feed. The guest picks a name and posts. No DMs, no security controls, no attack buttons.
- **How guest posts enter the mesh:** the host signs them as its own POSTs with body `"[guest " + name + "] " + text`, so they obey the host's 3-per-epoch limit. Other nodes show them as "guest of B". Guests get no keys; say this plainly to judges.
- **Endpoints on 8090:** `GET /` (page), `GET /api/feed?pin=` (JSON array of Msg), `POST /api/guest-post?pin=` with `{"name","text"}`. Wrong PIN returns 403. Per-guest IP limit: 1 post per 5 s. Guests poll `/api/feed` every 2 s; no WebSocket needed.
- **Plain HTTP is fine here.** The guest page uses only fetch, no camera, crypto or service worker, so Safari's secure-context rules do not apply.
- The iPhone will warn that the hotspot has no internet. Tap to stay connected, and keep the iPhone's mobile data off so it does not switch away.

---

### 6. Frozen interfaces

```kotlin
package mesh.core

typealias LinkId = String

interface Transport {
    val name: String                                  // "ble", "lan", "sim"
    fun start(listener: TransportListener)
    fun stop()
    /** Queue one frame (frameType + payload, no length prefix). False if the link is gone or its queue is full. */
    fun send(link: LinkId, frame: ByteArray): Boolean
    fun close(link: LinkId)
}

interface TransportListener {                          // may be called from any thread
    fun onLinkUp(link: LinkId, description: String)    // e.g. "ble 4A:11:..", "lan 192.168.43.12:9001"
    fun onFrame(link: LinkId, frame: ByteArray)        // one whole frame, length prefix already removed
    fun onLinkDown(link: LinkId)
}

interface Scheduler {                                  // real impl: single-thread executor; test impl: virtual time
    fun nowMs(): Long
    fun post(task: () -> Unit)
    fun schedule(delayMs: Long, task: () -> Unit)
}

enum class DebugFlag { TAMPER_RELAY, DROP_RELAY }
enum class Attack { FLOOD, SPOOF, REPLAY }

class MeshNode(
    dataDir: java.io.File,
    scheduler: Scheduler,
    transports: List<Transport>,
) {
    fun start()
    fun stop()
    fun post(text: String)
    fun sendDm(to: String /* nodeId hex */, text: String)
    fun sendDmImage(to: String, jpeg: ByteArray)       // stretch 2: one kind-1 DM, JPEG (FF D8 FF) of 39,999 bytes at most
    fun setName(name: String)
    fun setMuted(nodeIdHex: String, muted: Boolean)
    fun setVerified(nodeIdHex: String, verified: Boolean)
    fun setDebug(flag: DebugFlag, on: Boolean)
    fun attack(kind: Attack)
    fun snapshot(): Snapshot                           // full state for a UI that just connected
    fun addListener(l: (MeshEvent) -> Unit)            // called on the mesh loop
}

sealed interface MeshEvent
// MessageAdded(msg), StatusChanged(id, status, hops, via), NetworkChanged(contacts, links), SecurityLogged(event)
// Group additions (createGroup, setGroupMembers, sendGroup, GroupChanged, GroupStatusChanged): section 13.2
```

#### UI protocol (JSON, same shapes over WebSocket `/ws` and HTTP)

Server to browser:

```json
{"t":"snapshot","self":{"id":"a3f29c01d4e5b6f7","name":"A"},
 "contacts":[{"id":"...","name":"C","verified":false,"hops":2,"via":"<neighbourId or null>","linked":false,"muted":false,"lastSeenMs":0}],
 "links":[{"link":"ble:4A:..","peer":"<id or null>","transport":"ble","authed":true}],
 "feed":[Msg],"dms":{"<peerId>":[Msg]},"security":[Sec],"debug":{"tamper":false,"drop":false}}
{"t":"msg","msg":Msg}
{"t":"status","id":"<msgId>","status":"DELIVERED","hops":2,"via":"<id>"}
{"t":"net","contacts":[...],"links":[...]}
{"t":"sec","event":Sec}
```

`Msg = {"id","from","to" (null for posts),"text","lamport","tsMs","mine","status","hops","via","img"}`; `img` is the base64 JPEG of a photo DM (its `text` is `(photo)`), otherwise null. The page shows it only if it starts with `/9j/` (FF D8 FF), via a `data:` URL.
`Sec = {"tsMs","kind","detail","link","src"}` with `kind` one of `MALFORMED, BAD_POW, BAD_SIGNATURE, STALE, RATE_EQUIVOCATION, LINK_FLOOD, LINK_CLOSED, BANNED`.

Browser to server (WebSocket message, or HTTP `POST /api/cmd` with the same JSON body):

```json
{"t":"post","text":"..."}
{"t":"dm","to":"<id>","text":"..."}
{"t":"dm","to":"<id>","img":"<base64 JPEG, 39,999 bytes at most so kind byte + JPEG fit MAX_DM_PLAINTEXT>"}
{"t":"name","name":"..."}
{"t":"mute","id":"<id>","on":true}
{"t":"verify","id":"<id>","on":true}
{"t":"debug","flag":"tamper","on":true}
{"t":"attack","kind":"flood"}
```

Also `GET /api/snapshot` returns the snapshot object, `GET /api/qr?text=` (1 to 200 characters) returns that text as a QR code in SVG (stretch 5: the Network tab shows `omesh:<nodeId>`; guest join URLs), and `GET /api/guest` returns `{"pin","urls"}` while guest mode (5.13) is on, else `null`. The HTTP forms exist so shell scripts can drive a node with `curl`.

The server binds **127.0.0.1 only**. Anything that reaches this port can send messages as this node.

---

### 7. UI specification: keep it plain

One file, `uiserver/src/main/resources/web/index.html`, with inline CSS and JavaScript. No frameworks, no build step, no external requests.

**Look (replaced by 13.3; kept for history):** system font, black text on white, default buttons and inputs, one-pixel grey borders. No dark theme, gradients, animations, icons, canvas or charts. CSS stays under about 40 lines. It must be readable on a phone held at arm's length: 16 px base font, full-width inputs.

**Layout:** a header line and four sections switched by four plain buttons.

```
OfflineMesh   A (a3f2 9c01 d4e5 b6f7)   links: 2
[Feed] [Messages] [Network] [Security]
```

| Section | Contents |
|---|---|
| Feed | list of posts (`name: text`, then small grey `2 hops via B`), a text input, a Post button |
| Messages | a `<select>` of contacts, the conversation as a list, each of my messages ends with its status (`Queued`, `Sent`, `Delivered, 2 hops via B`), a text input, a Send button |
| Network | my name (editable) and fingerprint; table of contacts: Name, ID, Hops, Via, Linked, Verified (checkbox), Muted (checkbox); table of links: Transport, Peer, State |
| Security | newest-first list of security events as plain text lines; below it a "Demo attacks" box with two checkboxes (Tamper while relaying, Drop while relaying) and three buttons (Flood, Spoof, Replay) |

The page reconnects its WebSocket every 2 seconds when it drops and re-renders from the snapshot.

---

### 8. Workstreams

If there is one agent, ignore the split and follow section 9. Paths listed are the only ones that agent edits.

#### Agent A: protocol core. Owns `core/`

1. Create the Gradle root and all five modules as empty shells so everyone compiles. Publish section 6 as real Kotlin with stub bodies within the first 45 minutes.
2. `Identity` (generate, save, load), `Codec` (strict parse and serialise for frames and envelopes), `Crypto` (sign, verify, ECDH, AES-GCM), `Pow` (mine, verify).
3. `Security` (pipeline 5.6, link scores, bans, ledger), `Router` (5.7), `SyncEngine` (5.8), `Store` (5.11), `MeshNode` (handshake 5.5, presence timer, status tracking, red-team hooks 5.12).
4. `SimTransport` and `SimNetwork` with virtual time in `core/src/test`, and every test in 10.1 and 10.2.

Done when `./gradlew :core:test` is green and prints the transmission counts.

#### Agent B: Android. Owns `app/` and `scripts/deploy-all.sh`, `scripts/forward-ui.sh`

1. **Spike first (time-boxed to 2 hours):** a throwaway screen where two phones advertise, scan, open an L2CAP channel and exchange a line of text. Try it first on two emulators (10.3a) while the human sets up the phones, then on the OPPO and the Fold 7. Report both results before doing anything else.
2. Manifest and runtime permissions (below), Bluetooth-enabled check.
3. `BleL2capTransport` implementing `Transport` (details below).
4. `MeshService`: foreground service of type `connectedDevice` that owns the `MeshNode`, the transports and the UI server. `MainActivity`: a WebView that loads `http://127.0.0.1:8080` after the service reports the server is listening (no fixed delay).
5. A small native settings row above the WebView: transport toggles (Bluetooth, Wi-Fi LAN). Default Bluetooth on, LAN off.
6. Scripts: build, install on every connected phone, grant permissions, launch; forward each phone's port 8080 to laptop ports 8081, 8082, 8083.

Done when acceptance checks 1 to 6 pass on three phones.

**Permissions**

- API 31+: `BLUETOOTH_SCAN` with `android:usesPermissionFlags="neverForLocation"`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`.
- API 29 and 30: `BLUETOOTH`, `BLUETOOTH_ADMIN` (both `maxSdkVersion="30"`), `ACCESS_FINE_LOCATION`, and Location must be switched on or scans return nothing.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` (API 34), `POST_NOTIFICATIONS` (API 33+), `INTERNET` (for the localhost server), and for LAN: `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`.
- Cleartext to localhost is blocked by default. Add a network security config that permits cleartext for `127.0.0.1` and `localhost` only.
- Start the service only when permissions are actually granted.

**`BleL2capTransport` details**

- Server: `adapter.listenUsingInsecureL2capChannel()`, read `psm`, accept in a loop on its own thread.
- Advertise (connectable, low latency): advertising data holds the 128-bit service UUID only, device name excluded. The **scan response** holds service data for that UUID: `4-byte advert tag || 2-byte PSM`. The tag is 4 random bytes (SecureRandom) drawn on every transport start, not the NodeId prefix, so a passive listener cannot link sessions (the PSM is still fixed within one session). It serves only the dial tie-break; identity is proven in `HELLO_AUTH`. Putting both in one packet exceeds the 31-byte limit and fails with `ADVERTISE_FAILED_DATA_TOO_LARGE`.
- Scan: one `ScanFilter` on the service UUID, low-latency mode. From each result read the service data.
- Who connects: connect only if `myTag < peerTag` (unsigned byte compare), or if this phone cannot advertise (`bluetoothLeAdvertiser == null`). This prevents two phones dialling each other at once.
- Connect with `scanResult.device.createInsecureL2capChannel(psm)` then `connect()` on an I/O thread. **Use the `BluetoothDevice` object from the scan result**, not one rebuilt from an address string; the address is random and its type matters. Close the socket from a watchdog after 10 s. Retry with backoff 1, 2, 4 s, five attempts.
- Per link: one reader thread (`readInt`, range-check against `MAX_FRAME`, `readFully`) and one single-thread writer with a bounded queue of 64 frames. Any `IOException` closes the link and reports `onLinkDown`.
- Respect `MAX_LINKS`. If the server socket is recreated, re-advertise the new PSM.
- No pairing dialog should ever appear. If one does, something is using the secure variant.

**`LanTransport` on Android** is Agent C's class from the `lan/` module, used unchanged. In `app/`, hold a `WifiManager.MulticastLock` while it runs so UDP discovery packets are not filtered.

#### Agent C: UI, server, desktop. Owns `uiserver/`, `lan/`, `desktop/`, `scripts/run-3-desktop.sh`, `scripts/e2e.sh`

1. `UiServer(node, port)`: static page, `/ws`, `/api/snapshot`, `/api/cmd`, bound to 127.0.0.1. Start with a fake `MeshNode` that emits sample events so the page can be built before the core exists.
2. `index.html` per section 7.
3. `LanTransport` (in `lan/`, no Android imports): TCP listener plus optional UDP broadcast discovery (port 47474, every 2 s, payload `"OMESH1" || 4 random bytes || u16 tcpPort`, the random tag drawn fresh per transport instance so the beacon is not a tracking handle), plus static peers from the command line. Same tie-break as Bluetooth for discovered peers (smaller random tag dials).
4. `desktop` main: `--data <dir> --ui-port <n> --listen <n> [--peer host:port]... [--no-discovery]`.
5. `run-3-desktop.sh`: three nodes on one laptop wired in a line (A↔B, B↔C, discovery off), UIs on 8081 to 8083.
6. `e2e.sh`: drives three UIs through `curl` (works against desktop nodes and against phones through `adb forward`) and checks acceptance items 1 to 6. For item 6 it mutes B and C toward each other to create the partition, then unmutes.

Done when `run-3-desktop.sh` plus `e2e.sh` pass.

#### Agent D or the human: testing, demo, submission. Owns `docs/`, `core/src/test/kotlin/mesh/redteam/`

1. Extra adversarial tests against the core (anything not in 10.2).
2. `docs/THREATS.md`: the table in section 11 plus the limitations in section 12, in plain words.
3. `docs/DEMO.md`: the script in section 11, rehearsed and timed to under 3 minutes.
4. Devpost text, a 60-second backup video of the working demo, the measured numbers from the simulator.

---

### 9. Phases, time budgets and gates

Times are from the moment the agent starts. Freeze features 3 hours before the submission deadline.

| Phase | Budget | Work | Exit |
|---|---|---|---|
| 1 | 0:45 | Repo, modules, section 6 as compiling stubs | everything builds |
| 2 | 2:15 | Core codec, crypto, pipeline, routing, sync; simulator; **in parallel** the Bluetooth spike and the UI page on fake data | **Gate 1** |
| 3 | 3:00 | Desktop node, LAN transport, UI wired to the real node | **Gate 2** |
| 4 | 4:00 | Android service, real Bluetooth transport, two phones then three | **Gate 3** |
| 5 | 3:00 | Red-team hooks on phones, persistence, partition-heal check, `e2e.sh` on phones | acceptance 1 to 6 |
| 6 | rest | Stretch items in order, then freeze, rehearse, record video | submitted |

**Gate 1 (about 3 h):** simulator shows A → B → C DM with ACK and B unable to decrypt. The Bluetooth spike moved bytes between two phones.
*If the spike has not worked by 4 h:* stop Bluetooth work. Phones use `LanTransport` over one phone's hotspot (mobile data off). Tell the human. The pitch becomes "transport-independent mesh, shown over Wi-Fi".

**Gate 2 (about 6 h):** three desktop nodes pass `e2e.sh`. This is the guaranteed fallback demo. Record a video of it now.

**Gate 3 (about 10 h):** three phones relay a DM over Bluetooth. Only after this gate may stretch work begin.

---

### 10. Testing

#### 10.1 Unit tests (`core`)

- Codec: round trip for every frame and envelope type; truncated, oversized and trailing-garbage inputs are rejected.
- Crypto: sign and verify; one flipped bit fails; ECDH gives the same key on both sides; GCM round trip; wrong key, wrong IV and wrong associated data all fail.
- PoW: mined nonce verifies; nonce minus one does not (with overwhelming likelihood, retry-safe).
- Identity survives a reload from disk.

#### 10.2 Simulator tests (real `MeshNode`s on `SimTransport`, virtual time)

| # | Scenario | Must hold |
|---|---|---|
| 1 | Line A–B–C, A posts | C receives it once, `hops = 2` |
| 2 | Line, A DMs C | C gets plaintext; A sees `DELIVERED, 2 hops`; B emits no `MessageAdded` and cannot decrypt the body with its own key |
| 3 | Diamond A–B–D, A–C–D with E hanging off B; after presence settles A DMs D | E never receives the DM (unicast, not flood) |
| 4 | Line with link B–C down; A posts; link comes up | C has the post within one sync round |
| 5 | B has `TAMPER_RELAY`; A posts | C logs `BAD_SIGNATURE` against the link to B; A is not banned at C |
| 6 | Line A–B–C, A runs attack `flood` | B accepts three posts, logs `RATE_EQUIVOCATION` on the fourth and bans A; C holds exactly three posts; A's next post is not relayed by B |
| 7 | M sends a POST with A's public key and a random signature | neighbours log `BAD_SIGNATURE`, penalise the link to M; A is not banned |
| 8 | Replay of an accepted envelope | dropped as duplicate, no security event, nothing forwarded |
| 9 | Random connected graph, 25 nodes, one post | all 25 receive it; print transmissions and assert `≤ 2 × links` |
| 10 | M sends 200 well-formed envelopes in 2 s | link to M is closed, `LINK_FLOOD` logged |
| 11 | Node restarts mid-epoch | first post waits for the next epoch; no ban |

#### 10.3 On phones (no MCP servers needed)

```
./gradlew :app:assembleDebug
scripts/deploy-all.sh          # for every serial in `adb devices`: install -r, pm grant, am start
scripts/forward-ui.sh          # adb -s <serial> forward tcp:808N tcp:8080
scripts/e2e.sh 8081 8082 8083  # same checks as the desktop run
adb -s <serial> logcat -s OfflineMesh
```

The laptop browser can open `localhost:8081` to `8083` side by side. Use this on the projector during the demo.

**If there are only two Android phones:** the third node is the desktop node on a laptop joined to phone B's hotspot, with LAN transport on for B and the laptop. The path is A –Bluetooth– B –Wi-Fi– C. Say so in the demo; it shows two transports in one path.

#### 10.3a Three emulated phones on the laptop (before and alongside real phones)

The Android Emulator has a shared virtual Bluetooth radio (netsim): every emulator started on the same laptop can scan, advertise and connect to the others. `adb` sees them as `emulator-5554`, `emulator-5556`, `emulator-5558`, so `deploy-all.sh`, `forward-ui.sh` and `e2e.sh` work unchanged.

1. Android Studio → Device Manager → create three virtual devices (e.g. Pixel 8) on an API 34 or newer Google APIs image. Names: `meshA`, `meshB`, `meshC`.
2. Start each one: `emulator -avd meshA &` (and B, C). Check Bluetooth is on inside each one; if not, enable it in the emulator's settings.
3. Install and run the app on all three with the scripts. Use muting (5.9) to force A → B → C, since all emulators hear each other.
4. Optional: `emulator -avd meshA -netsim-args="--pcap"` captures the Bluetooth packets for a Wireshark screenshot.

**Use emulators to:** debug the Android service, permissions, the WebView and the BLE transport code without unplugging phones. **Do not trust them for:** OEM battery killing (OPPO, Samsung), real range and timing, or proof that L2CAP works on the actual phones. L2CAP connection-oriented channels between emulators are not verified; if the Bluetooth spike fails on emulators but works on phones, stop using emulators for Bluetooth and keep them for UI and service work. Three emulators need roughly 6 to 9 GB of RAM; close one if the laptop struggles.

#### 10.4 The team's phones

| Role | Phone | Notes |
|---|---|---|
| A, sender | OPPO Find X9 Pro | ColorOS kills background apps hardest; test the service here first |
| B, relay, hotspot host for guests | Samsung Galaxy Z Fold 7 | Newest Bluetooth stack; folding or unfolding recreates the activity, so the WebView must reload from the snapshot (the service keeps running) |
| C, receiver | Samsung Galaxy A52s | Oldest phone; if the spike fails, test it on A and B first to see whether the A52s is the problem |
| Guest | iPhone 18 Pro Max | Browser only, via 5.13. It cannot run the Android app or join the Bluetooth mesh |

**One-time setup on every Android phone (the human does this):**

1. Settings → About phone → tap Build number 7 times → Developer options → USB debugging on. On the OPPO, if `adb install` or `pm grant` fails, look in Developer options for "Disable permission monitoring" and any USB install verification toggle (names vary by ColorOS version).
2. Plug in and accept the "Allow USB debugging" prompt. `adb devices` must list all three.
3. Battery, after the first install:
   - Samsung (Fold 7, A52s): Settings → Apps → OfflineMesh → Battery → **Unrestricted**. Also turn off "Put unused apps to sleep" in Battery settings.
   - OPPO: App info for OfflineMesh → Battery usage → allow background activity and auto launch (wording varies).
   - All three: `adb -s <serial> shell dumpsys deviceidle whitelist +com.meshnet.offline`
4. During the demo keep screens on while plugged in: `adb -s <serial> shell svc power stayon usb`.
5. Airplane mode on, then Bluetooth back on (and Wi-Fi on only for B's hotspot or the LAN fallback). Location on for the A52s if it is on Android 11 or lower; it does no harm on the others.

**LAN fallback with these phones:** B (Fold 7) turns on its hotspot with mobile data off; A and C join it and tap "stay connected" at the no-internet warning; all three switch the transport toggle to Wi-Fi LAN. The protocol is unchanged.

---

### 11. Demo script and judge questions

**Script (under 3 minutes):**

1. Show all three phones in airplane mode with Bluetooth on. Show the Network tab: A sees C at "2 hops via B".
2. A sends C a DM. C shows it. A shows "Delivered, 2 hops via B". B's Messages tab stays empty.
3. Turn on "Tamper while relaying" on B. A sends again. C's Security tab shows the rejection. Turn it off.
4. Press Flood on A. B shows the ban; C shows only the first three posts.
5. Turn off Bluetooth on C, post from A, turn Bluetooth on. The post appears on C.
6. One sentence on limits (section 12).

**Honest answers to the four TDS questions**

| Question | Answer to give |
|---|---|
| How do you stop mass-message spam? | Three layers. Each neighbour link has a token bucket, and a link that misbehaves is closed. Public posts are limited to 3 per 10 seconds per key; breaking that requires signing two messages for one slot, which is proof anyone can check, and the key is banned. Proof of work adds a small cost per message. We do not claim proof of work stops a determined attacker by itself. |
| How do you stop malicious people joining or spoofing? | Anyone can join; this is an open emergency network. Nobody can impersonate an existing identity: the ID is a 64-bit hash of the public key and every message is signed. People confirm contacts by comparing fingerprints in person. We do not stop one attacker creating many identities. |
| How do you stop a message reaching a million people? | DMs follow one path, not a flood. Broadcasts are forwarded once per link, never back to a neighbour that has them, and stop after 6 hops, so cost grows with the number of links nearby, not with the size of the network. |
| How do you keep privacy and integrity across relays you do not trust? | DMs are encrypted end to end with AES-256-GCM using a key only the two endpoints can derive. Every message is signed, and its ID is a hash of its content, so any change is detected at the next honest node. |

---

### 12. Known limitations (say these, do not hide them)

- **Many fake identities (Sybil):** not prevented. Limits are per identity.
- **Proof of work is weak at these difficulties.** 15 to 16 bits is about 33,000 to 66,000 hashes: tens of milliseconds on a phone, and on the order of a thousand messages per second for an attacker with a laptop (estimate, not measured; measure it and quote the real figure). It is a cost multiplier, not a gate.
- **Backdating:** a sender can claim any epoch inside the one-hour retention window, so the true ceiling is 3 posts per epoch across 360 epochs, each still costing proof of work.
- **Clocks:** rate limiting assumes phone clocks agree within about 20 seconds.
- **Metadata:** relays see who is talking to whom (source and destination IDs), message sizes and timing.
- **No forward secrecy.** A stolen private key decrypts past DMs.
- **One key for signing and key agreement.**
- **Black-hole relay:** if the only path runs through a node that drops traffic, messages do not arrive. Sync recovers only when another path exists.
- **TTL can be reset by a malicious relay** to its original value. Duplicate suppression still stops storms.
- **Local UI port:** another app on the same phone could talk to 127.0.0.1:8080.
- **Desk demo uses software muting** to force two hops.
- **Android only.**

---

### 13. Owner additions (2026-11-14, after the first build): any number of phones, group chats, a nicer UI

The owner asked for these after acceptance 1 to 6 passed. They come after everything above and must not break it: `e2e.sh 8081 8082 8083` items 1 to 6 still pass, and the desktop fallback still works.

#### 13.1 Any number of phones

The protocol already works for any number of nodes (10.2 #9 runs 25 nodes; the redteam suite runs 50). Each phone keeps at most `MAX_LINKS` (5) direct links; more phones simply form more hops. What changes:

- `scripts/run-3-desktop.sh`: optional `NODES=N` (default 3, max 10). Nodes named A, B, C, D, ... wired in a line, UIs on `UI_BASE` to `UI_BASE+N−1`. With `NODES` unset it behaves exactly as before.
- `scripts/e2e.sh` accepts any number of ports (3 or more). Items 1 to 6 use the first three, unchanged. New items, run **before item 5** (item 5 bans the first node for 10 minutes):
  - **7. Group:** node 1 creates a group of every node, sends a group message; every other node shows it once in that group with the same id; node 1 shows `Delivered to N−1 of N−1` within 30 s.
  - **8. Reach:** a post from node 1 reaches every node once (only checked when more than 3 ports are given).
- `deploy-all.sh` and `forward-ui.sh` already handle every connected device; keep them that way and print the serial→port map.
- Emulators: a fourth AVD `meshD` may be used for item 8 if the laptop has the RAM; otherwise test N > 3 on desktop nodes.
- Acceptance (section 1) gains: **7.** With 3 or more phones, a group of all phones: a message from one phone reaches every other phone and the sender shows "Delivered to N−1 of N−1".

#### 13.2 Group chats (fan-out over DMs)

A group is an 8-byte `groupId` (shown as hex) bound to its owner: `groupId` = first 8 bytes of SHA-256(`ownerNodeId` (8 bytes) ‖ `salt` (8 random bytes)). It also has a name (32 bytes or less), an owner (the creator's NodeId) and a member list of 16 NodeIds or fewer, including the owner. Groups exist only on member devices.

**No new envelope type, frame, crypto or ingress rule.** Every group message is sent as one ordinary DM (5.3, 5.4) to each other member, so relays see only DMs and each copy is end-to-end encrypted and ACKed on its own. DM plaintext `kind` gains two values:

| kind | Name | Data after the kind byte |
|---|---|---|
| 2 | GROUP_INVITE | `8B groupId` `8B salt` `u8 nameLen` `name` `u8 count` then `count` × (`u8 pubLen` `pub`) |
| 3 | GROUP_TEXT | `8B groupId` `8B groupMsgId` then UTF-8 text, 1,000 bytes or less |

- **Create:** the owner sends GROUP_INVITE to every other member. The invite carries each member's public key, so every member can encrypt to every other member at once; member ids are derived from the keys (5.1), so a key cannot be swapped for another id. Member keys stay inside the group record (for fan-out encryption and reopening own DMs); they are never added to contacts.
- **Membership changes:** only the owner can change the list, by sending a new GROUP_INVITE to every old and new member. A receiver drops an invite unless `groupId` = SHA-256(`srcId` ‖ `salt`)[0..8] and the sender is in the list, so only the owner can create or claim a `groupId`; a change to an existing group is accepted only from its recorded owner. Caps: 32 live groups normally, 64 hard, and at most 4 per owner unless that owner is verified; invites over a cap are dropped silently. A member left out of the new list removes the group's live state, keeps its history, and gets no further messages because nobody sends to it.
- **Send:** pick a random `groupMsgId`, send GROUP_TEXT as a DM to each other member. The sender shows one message, not N−1. Its status is `Delivered to k of n`, counting ACKs of the fan-out DMs.
- **Receive:** show GROUP_TEXT only if the group is known and the sender is a member; otherwise drop it silently. De-duplicate on (`groupId`, `senderId`, `groupMsgId`), so one member reusing another's `groupMsgId` cannot suppress it. The UI id of a group message is `senderNodeIdHex` + `groupMsgIdHex` (32 hex chars), the same on every member and on the sender's own aggregated message.
- **Persistence:** invites and group texts are DMs, so replaying `messages.log` (5.11) rebuilds groups and their history. Nothing new on disk.
- **Limits:** a group message costs one DM (with its own PoW) per other member; 16 members keeps that small. Relays can see that one source sent DMs to several ids at the same moment. A full 32-group table evicts its oldest evictable group (owner not me, not a verified contact, and I have never posted in it; history kept) for any new invite; with nothing evictable, an unverified invite is dropped and a verified one is admitted up to 64 groups, so cheap identities can neither evict groups I use nor lock out real contacts. State all three in `docs/THREATS.md`.

**Interface additions (section 6 is extended by these, same rules as section 6):**

```kotlin
// MeshNode
fun createGroup(name: String, members: List<String /* nodeId hex */>): String   // returns groupId hex
fun setGroupMembers(groupId: String, members: List<String>)                    // owner only, else ignored
fun sendGroup(groupId: String, text: String)
// MeshEvent: GroupChanged(group), GroupStatusChanged(id /* sender hex + groupMsgId hex */, delivered, total)
```

UI protocol: snapshot gains `"groups":[{"id","name","owner","members":["<id>",...]}]` and `"gdms":{"<groupId>":[Msg]}`. `Msg` gains `"group"` (groupId, null otherwise) and, on own group messages, `"delivered"` and `"total"`. New server events `{"t":"group","group":{...}}` and `{"t":"gstatus","id":"<senderId><groupMsgId>","delivered":k,"total":n}`; group messages arrive as normal `{"t":"msg"}` events. New commands `{"t":"group","name":"...","members":["<id>",...]}`, `{"t":"group-members","id":"<gid>","members":[...]}`, `{"t":"gmsg","group":"<gid>","text":"..."}`.

**Tests (add to 10.2):**

| # | Scenario | Must hold |
|---|---|---|
| 12 | Random connected graph, 10 nodes; A creates a group {A, D, G, J} and sends | D, G, J each emit one `MessageAdded` with that group and the same id (A's id + `groupMsgId`); no other node emits `MessageAdded`; A ends at delivered 3 of 3 |
| 13 | D (a member, not the owner) sends to the group | A, G and J receive it once |
| 14 | GROUP_TEXT from a non-member; GROUP_INVITE change from a non-owner; owner removes J then sends | first two ignored; J receives nothing after removal |
| 15 | A member restarts | its groups and group history come back from `messages.log` |
| 16 | B sends D an invite whose `groupId` is bound to A, then one bound to B listing an unknown key | first ignored; second accepted and the unknown key is not a contact |
| 17 | Owner removes J; G sends J an invite with A's real `groupId` and salt; owner re-adds J | J ignores G's claim; J ends with A's group, owner A |
| 18 | Unverified B sends D 5 valid invites, then a 6th after D verifies B | D keeps only the first 4 of B's; the 6th is accepted |
| 19 | D and G send GROUP_TEXT to J with the same `groupMsgId` | J shows both, with ids `D+groupMsgId` and `G+groupMsgId` |

**Android:** group messages raise the same notification as DMs, titled with the group name, and inline reply sends to the group.

#### 13.3 UI refresh (replaces the **Look** paragraph of section 7)

Layout, the four sections and the protocol of section 7 stay, except that **Messages** becomes **Chats** and lists DMs and groups together. The page stays light:

- Still one file, `index.html` (and `guest.html` in the same style), with inline CSS and JavaScript. No frameworks, no build step, no web fonts, no icon files, no external requests. Under about 60 KB per page; CSS under about 150 lines.
- **Look:** system font stack, 16 px base, spacing on an 8 px grid, content max 680 px wide and centred. CSS variables for colours with a light and a dark set chosen by `prefers-color-scheme`, no toggle. One accent colour. 1 px borders, 8 to 12 px corner radius. No gradients, no shadows beyond one subtle one on the compose bar, no animations except a 150 ms colour change on press. Text or Unicode only for symbols (✓, ✓✓, •).
- **Header:** sticky; app name, my name and short fingerprint, a links pill (accent with "2 links" when linked, grey "no links" otherwise).
- **Tabs:** Feed, Chats, Network, Security with unread counts. Bottom tab bar on screens narrower than 600 px, top bar on wider ones. Buttons and tabs at least 44 px tall.
- **Chats:** a list of conversations (contact or group name, last message preview, time, unread count, `2 hops via B` for DMs). Tapping one opens it full width with a back button. A **New group** button opens a form: name plus a checkbox list of known contacts. The group view shows its members, and for the owner, add/remove.
- **Messages:** bubbles, mine on the right with the accent colour, theirs on the left in a neutral colour. In groups, the sender's name above their bubble. A small grey line under each bubble: time, `2 hops via B`, and on mine the status: `Queued •`, `Sent ✓`, `Delivered ✓✓`, `Delivered to 3 of 4` for groups. A compose bar pinned to the bottom; Enter sends; the Send button is disabled while empty; a byte counter appears near the 1,000-byte limit; "slow down" shows inline when the node refuses a post.
- **Feed:** posts as cards, newest at the bottom, auto-scroll only if already at the bottom. `guest of B` shown as a small tag.
- **Network:** contacts as rows with Verified and Muted switches, fingerprints in monospace groups of four, links below. The QR code and guest PIN stay where they are.
- **Security:** events as rows with a coloured left border (red for `BAD_SIGNATURE`, `BANNED`, `RATE_EQUIVOCATION`; amber for the rest), and a clearly framed "Demo attacks" box.
- A thin "Reconnecting…" bar while the WebSocket is down (it still retries every 2 s and re-renders from the snapshot).
- **Speed on the A52s WebView:** no virtual DOM; re-render only the visible section; render at most the last 200 items of any list.
- Keep every element id or text that `e2e.sh` or the app relies on, or update them in the same commit.

---

### Appendix A. What changed from the earlier proposal, and why

| Earlier proposal | Verdict | This plan |
|---|---|---|
| BLE L2CAP sockets, no pairing (API 29+) | Sound. Highest-risk piece on real phones. | Kept, behind a 2-hour spike and a LAN fallback |
| 8 bytes of service data plus a service UUID in one advertisement | Does not fit: 47 bytes against a 31-byte limit | UUID in the advertisement, 6 bytes of data in the scan response |
| No rule for which phone dials | Both dial at once, duplicate links | Lower ID prefix dials |
| NodeId = 2 bytes of the key hash (other pages say 4) | An attacker matches a chosen ID in about 65,000 key generations; 300 honest nodes have a 50% chance of an accidental clash | 8 bytes |
| Ed25519/X25519 on one page, ECDSA P-256 in the code | Contradiction. Ed25519 through `java.security` needs Android 13 | P-256 everywhere |
| Key pair generated in the constructor | Identity changes on every app start, so pinned contacts break | Saved to disk |
| Bad signature or bad PoW penalises the claimed sender's key | Lets an attacker get any victim banned by forging packets in their name | Penalise the delivering link; ban a key only on signed proof |
| Hashcash "D = 53 bits, years of CPU" | The proposal's own formula tops out at 23 to 24 bits | Kept the formula, dropped the claim |
| Rate slots apply to every message | A 200 KB photo in 16 KB chunks would take about 50 seconds; ACKs would eat slots | Slots for public posts only; per-link bucket for the rest |
| Permanent ban, unbounded ledger | Restart mid-epoch self-bans; memory grows forever | 10-minute ban, start-up delay, eviction |
| Trickle suppression by "overhearing" neighbours | Point-to-point links cannot overhear; suppression can strand nodes | Jittered flood, never send to a neighbour that has it |
| 64-byte Bloom filter sync | About 47% false positives at 256 messages; missing messages never sync | Exact ID list (2 KB) |
| DM to a node two hops away | No way for A to learn C's public key | Signed PRESENCE flood, fingerprint check |
| Reverse-path table | Sound, but empty until the target has sent something | PRESENCE fills it; flood when no route |
| "TTL + hops is signed so TTL cannot be raised" | A relay can reset both consistently | Stated as a limitation |
| Captive portal redirect from the phone on port 8080 | Not possible on a stock phone hotspot | Dropped; guest link by QR is a stretch item |
| Ktor on `0.0.0.0:8080` | Any device on the network can send as that phone | Bound to 127.0.0.1 |
| WebView loads after a fixed 400 ms; cleartext not allowed | Race, and a blank page on Android 9+ | Wait for the server; network security config |
| Room/SQLite, chunked media, Bubbles, lock-screen reply | Too much for the time | File log; single-frame photo and notification as stretch |
| Python simulator and four MCP servers | The simulator does not run this code; the MCP packages were not verified and are not needed | Kotlin simulator on the real core; plain `adb`, `gradlew`, `curl` |
| Tactical dark console with canvas radar | More than needed, and its script was cut off before any logic | Plain page per section 7 |

---

### Appendix B. Kickoff prompts and setup

**Parts 1 to 4 of this guide supersede the steps below where they differ.** They cover downloads, permissions, looping with `/goal`, the test hook, the four agent definitions and the plugins (Ponytail on, Graphify only at the end). They work with any Claude Code login.

#### Before starting any agent (human, about 20 minutes)

1. On the laptop: Android Studio (current stable, includes JDK 17 and `adb`), Git, a terminal with `curl`. Run `adb devices` once to accept keys.
2. Create one empty GitHub repo, `offlinemesh`, and clone it once per agent (or use one clone per agent folder). Put this file at the repo root.
3. Do the phone setup in 10.4 steps 1 and 2 for all three Android phones.
4. Start Agent A first. Start B, C and D straight away too: B's spike and C's fake-data UI do not need A's code. B and C pull A's skeleton as soon as A pushes it (target 45 minutes).

#### Rules shared by every agent

- Commit and push small commits often to `main`; pull before every push. Only edit the paths your workstream owns.
- Section 6 is frozen; changing it needs an edit to this file and a note in `STATUS.md`.
- When you need a physical action on a phone, stop and ask the human exactly what to tap.

#### Prompt for Agent A (core)

```
Read OFFLINEMESH_COMPLETE_GUIDE.md fully. You are Agent A (section 8). First, within 45 minutes:
create the Gradle root with modules core, lan, uiserver, desktop, app as empty shells that
build, and section 6 as compiling Kotlin stubs in core; push. Then implement sections 4, 5.1–5.12
in core and the tests in 10.1 and 10.2 on a virtual-time simulator. Done = ./gradlew :core:test
green with the transmission counts printed. Log progress in STATUS.md.
```

#### Prompt for Agent B (Android)

```
Read OFFLINEMESH_COMPLETE_GUIDE.md fully. You are Agent B (section 8). Start with the 2-hour Bluetooth spike
in a throwaway module or app; the human will install it on the OPPO Find X9 Pro and Galaxy Z Fold 7
(see 10.4) and report whether text crossed. Report the result in STATUS.md before anything else.
Then build app/ as specified, using core from Agent A once it is pushed. Done = acceptance checks
1–6 pass on the three phones in 10.4. After Gate 3, build guest access (5.13) with Agent C.
```

#### Prompt for Agent C (UI, LAN, desktop)

```
Read OFFLINEMESH_COMPLETE_GUIDE.md fully. You are Agent C (section 8). Build uiserver with a fake MeshNode
first and the plain page in section 7 (no styling beyond section 7). Then lan/, desktop/,
run-3-desktop.sh and e2e.sh against the real core. Done = run-3-desktop.sh plus e2e.sh pass
(Gate 2). After Gate 3, build guest.html and the 8090 server for 5.13.
```

#### Prompt for Agent D (tests, docs, pitch)

```
Read OFFLINEMESH_COMPLETE_GUIDE.md fully. You are Agent D (section 8). Write extra adversarial tests in
core/src/test/kotlin/mesh/redteam/ once Agent A's simulator exists, docs/THREATS.md from sections
11 and 12, docs/DEMO.md from section 11, and the Devpost description. Collect the measured numbers
(transmissions per broadcast, PoW time on each phone). Do not edit other agents' paths.
```

---

# Part 6. Timeline at a glance

Assuming agents start around 2:00 PM Saturday.

| Time (CT) | Milestone |
|---|---|
| Sat 1:30 PM | Laptop setup, repo, files from Part 3, phones show in `adb devices` |
| Sat 2:00 PM | Four agents started with their goals |
| Sat 2:45 PM | Core skeleton pushed; everyone builds |
| Sat ~5:00 PM | **Gate 1:** simulator relays A → B → C; Bluetooth spike result known |
| Sat 6:00 PM | If the spike failed: switch phones to the Wi-Fi LAN fallback |
| Sat ~8:00 PM | **Gate 2:** three desktop nodes pass `e2e.sh`. Record a backup video |
| Sun ~12:00 AM | **Gate 3:** three phones relay a DM over Bluetooth |
| Sun ~3:00 AM | Acceptance checks 1 to 6 pass on phones; start stretch items (iPhone guest first) |
| Sun 8:00 AM | **Feature freeze.** Only bug fixes, rehearsal, video, Devpost text |
| Sun ~10:30 AM | Submit on Devpost (deadline about 11:00 AM; confirm) |
