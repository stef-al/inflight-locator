#!/usr/bin/env bash
# Compiles the engine and its tests with a plain Kotlin compiler (no Gradle project, no Android SDK needed).
# Usage: engine/build.sh            compile into engine/build/classes
# Needs `kotlinc` on the PATH, or KOTLINC_CP + KOTLIN_STDLIB pointing at compiler jars (for example the
# jars bundled with a Gradle distribution).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/classes
SRC=$(find src/main/kotlin src/test/kotlin -name '*.kt')
if command -v kotlinc >/dev/null 2>&1; then
  kotlinc $SRC -jvm-target 17 -d build/classes
  echo "compiled with kotlinc"
else
  GRADLE_LIB=${GRADLE_LIB:-/opt/gradle/lib}
  CP=$(ls "$GRADLE_LIB"/*.jar | tr '\n' ':')
  STD=$(ls "$GRADLE_LIB"/kotlin-stdlib-[0-9]*.jar | head -1)
  java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$STD" -jvm-target 17 -d build/classes $SRC
  echo "$STD" > build/stdlib.path
  echo "compiled with the Kotlin compiler bundled in $GRADLE_LIB"
fi
