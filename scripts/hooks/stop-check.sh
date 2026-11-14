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
