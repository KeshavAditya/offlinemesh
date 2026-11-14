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
