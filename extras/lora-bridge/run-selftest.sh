#!/usr/bin/env bash
# UNTESTED ON HARDWARE — not part of the demo build
# Compiles MeshtasticTransport.kt + test/SelfTest.kt against core.jar with the Kotlin compiler Gradle already
# downloaded, then runs the self-test. No radio needed. Usage (repo root): extras/lora-bridge/run-selftest.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
K=2.0.21
C=${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1
win() { if command -v cygpath >/dev/null; then cygpath -w "$1"; else echo "$1"; fi; }
jar() { local p; p=$(find "$C/$1" -name "$2" 2>/dev/null | head -1); [ -n "$p" ] || { echo "missing $1 in Gradle cache: run ./gradlew build once" >&2; exit 1; }; win "$p"; }
SEP=:; command -v cygpath >/dev/null && SEP=';'
STD=$(jar org.jetbrains.kotlin/kotlin-stdlib/$K "kotlin-stdlib-$K.jar")
KC="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable/$K '*.jar')$SEP$STD$SEP$(jar org.jetbrains.kotlin/kotlin-script-runtime/$K '*.jar')$SEP$(jar org.jetbrains.kotlin/kotlin-reflect '*.jar')$SEP$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable/$K '*.jar')$SEP$(jar org.jetbrains.intellij.deps/trove4j '*.jar')$SEP$(jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm 'kotlinx-coroutines-core-jvm-*[0-9].jar')$SEP$(jar org.jetbrains/annotations/13.0 'annotations-13.0.jar')"
./gradlew -q :core:jar
CORE=$(win core/build/libs/core.jar)
SER=$(jar org.jetbrains.kotlinx/kotlinx-serialization-core-jvm 'kotlinx-serialization-core-jvm-*[0-9].jar')
OUT=build/lora-selftest; rm -rf "$OUT"
java -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -nowarn -cp "$STD$SEP$CORE$SEP$SER" -d "$(win $OUT)" \
  extras/lora-bridge/src/MeshtasticTransport.kt extras/lora-bridge/test/SelfTest.kt
java -cp "$(win $OUT)$SEP$STD$SEP$CORE" mesh.extras.lora.SelfTestKt
