#!/usr/bin/env bash
# Compiles and runs the pure-Kotlin game core and its unit tests without Gradle or the Android SDK.
# Uses the Kotlin compiler bundled with a local Gradle install (GRADLE_HOME).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GH="${GRADLE_HOME:-/opt/gradle-8.14.3}"
L="$GH/lib"; P="$GH/lib/plugins"
OUT="${OUT_DIR:-$(mktemp -d)}"
jar() { { ls "$L"/$1 "$P"/$1 2>/dev/null || true; } | head -1; }
KCP="$(jar 'kotlin-compiler-embeddable-*.jar'):$(jar 'kotlin-stdlib-2*.jar'):$(jar 'kotlin-script-runtime-*.jar'):$(jar 'kotlin-reflect-*.jar'):$(jar 'kotlinx-coroutines-core-jvm-*.jar'):$(jar 'trove4j*.jar'):$(jar 'kotlin-daemon-embeddable-*.jar'):$(jar 'annotations-2*.jar')"
STD="$(jar 'kotlin-stdlib-2*.jar')"
TESTCP="$STD:$(jar 'junit-4*.jar'):$(jar 'hamcrest-core-*.jar')"
cd "$ROOT/app"
java -Dfile.encoding=UTF-8 -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -nowarn \
  -cp "$TESTCP" -d "$OUT" src/main/java/com/khumasi/game/core src/test/java/com/khumasi/game/core
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT:$TESTCP" org.junit.runner.JUnitCore \
  $(cd src/test/java && find com -name '*Test.kt' | sed 's/\.kt$//; s#/#.#g')
