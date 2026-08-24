#!/usr/bin/env bash
#
# Type-check the Android module's Kotlin sources without the Android SDK.
#
# Why this exists
# ---------------
# The app's real build needs the Android SDK and Google's Maven repository. In
# a CI runner or sandbox that can only reach Maven Central, `./gradlew :app:build`
# cannot run at all — which would mean the Android half of this project got no
# machine checking whatsoever, and hallucinated framework APIs would only be
# caught when someone opened Android Studio.
#
# So this script assembles a compiler that *is* reachable from Maven Central:
#
#   - the Kotlin compiler (kotlin-compiler-embeddable), and
#   - a real android.jar, borrowed from Robolectric's `android-all` artifact,
#     which carries the complete framework API surface, plus
#   - small local stubs for the handful of AndroidX/Hilt annotations used by the
#     verified sources (see tools/verification/stubs).
#
# It then runs the compiler frontend over the sources listed in
# tools/verification/verified-sources.txt and reports resolution and type
# errors. Backend code generation is expected to fail against the Robolectric
# jar and is deliberately ignored — only `error:` diagnostics matter here.
#
# What it does NOT cover
# ----------------------
# Compose UI, Room, Hilt code generation and anything else that needs the real
# AndroidX artifacts. Those are checked by a normal Gradle build. See
# docs/setup.md for the full picture.
#
# Usage:  tools/verify-android-sources.sh [cache-dir]
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="${1:-${JOHN_VERIFY_CACHE:-$ROOT/.verify-cache}}"
LIBS="$CACHE/libs"
MAVEN="${JOHN_MAVEN_MIRROR:-https://repo1.maven.org/maven2}"

# Must track `kotlin` in gradle/libs.versions.toml. A compiler older than the
# one that built :core cannot read its metadata, and every verified file then
# fails with "unresolved reference" against a jar that is perfectly fine.
KOTLIN_VERSION="2.3.20"
COROUTINES_VERSION="1.9.0"
SERIALIZATION_VERSION="1.7.3"
ANDROID_ALL="14-robolectric-10818077"

mkdir -p "$LIBS"

fetch() {
  local url="$1" name
  name="$(basename "$url")"
  if [[ ! -f "$LIBS/$name" ]]; then
    echo "  fetching $name"
    curl -sSL --fail --max-time 600 -o "$LIBS/$name" "$url"
  fi
  echo "$LIBS/$name"
}

echo "Resolving verification toolchain into $LIBS"
COMPILER=$(fetch "$MAVEN/org/jetbrains/kotlin/kotlin-compiler-embeddable/$KOTLIN_VERSION/kotlin-compiler-embeddable-$KOTLIN_VERSION.jar")
STDLIB=$(fetch "$MAVEN/org/jetbrains/kotlin/kotlin-stdlib/$KOTLIN_VERSION/kotlin-stdlib-$KOTLIN_VERSION.jar")
REFLECT=$(fetch "$MAVEN/org/jetbrains/kotlin/kotlin-reflect/$KOTLIN_VERSION/kotlin-reflect-$KOTLIN_VERSION.jar")
SCRIPT_RT=$(fetch "$MAVEN/org/jetbrains/kotlin/kotlin-script-runtime/$KOTLIN_VERSION/kotlin-script-runtime-$KOTLIN_VERSION.jar")
DAEMON=$(fetch "$MAVEN/org/jetbrains/kotlin/kotlin-daemon-embeddable/$KOTLIN_VERSION/kotlin-daemon-embeddable-$KOTLIN_VERSION.jar")
TROVE=$(fetch "$MAVEN/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar")
INJECT=$(fetch "$MAVEN/javax/inject/javax.inject/1/javax.inject-1.jar")
COROUTINES=$(fetch "$MAVEN/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/$COROUTINES_VERSION/kotlinx-coroutines-core-jvm-$COROUTINES_VERSION.jar")
SERIALIZATION=$(fetch "$MAVEN/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/$SERIALIZATION_VERSION/kotlinx-serialization-json-jvm-$SERIALIZATION_VERSION.jar")
SERIALIZATION_CORE=$(fetch "$MAVEN/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/$SERIALIZATION_VERSION/kotlinx-serialization-core-jvm-$SERIALIZATION_VERSION.jar")
echo "  fetching android-all (~132 MB, cached after the first run)"
ANDROID_JAR=$(fetch "$MAVEN/org/robolectric/android-all/$ANDROID_ALL/android-all-$ANDROID_ALL.jar")

# The JVM core is a normal Gradle module; build it so verified sources can
# resolve against real classes rather than another set of stubs.
echo "Building :core"
"$ROOT/gradlew" -p "$ROOT" -PskipAndroidModules=true :core:jar -q --console=plain
# Named exactly, not globbed: core/build/libs also holds core-test-fixtures.jar
# once :core:test has run, and it sorts first. Picking it silently drops every
# main-source class and fails the whole run with "unresolved reference".
CORE_JAR="$(find "$ROOT/core/build/libs" -name 'core.jar' | head -1)"

COMPILER_CP="$COMPILER:$STDLIB:$REFLECT:$SCRIPT_RT:$DAEMON:$TROVE:$COROUTINES"
TARGET_CP="$ANDROID_JAR:$STDLIB:$REFLECT:$INJECT:$COROUTINES:$SERIALIZATION:$SERIALIZATION_CORE:$CORE_JAR"

SOURCES_FILE="$ROOT/tools/verification/verified-sources.txt"
mapfile -t PATTERNS < <(grep -vE '^\s*(#|$)' "$SOURCES_FILE")

SOURCES=()
for pattern in "${PATTERNS[@]}"; do
  while IFS= read -r file; do SOURCES+=("$file"); done < <(find "$ROOT/$pattern" -name '*.kt' 2>/dev/null || true)
done
while IFS= read -r file; do SOURCES+=("$file"); done < <(find "$ROOT/tools/verification/stubs/src" -name '*.kt')

if [[ ${#SOURCES[@]} -eq 0 ]]; then
  echo "No sources matched $SOURCES_FILE" >&2
  exit 1
fi

echo "Type-checking ${#SOURCES[@]} files against android-all $ANDROID_ALL"
OUTPUT="$CACHE/compiler-output.txt"
set +e
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -nowarn -jvm-target 17 \
  -cp "$TARGET_CP" \
  -d "$CACHE/out" \
  "${SOURCES[@]}" > "$OUTPUT" 2>&1
set -e

# Backend codegen against the Robolectric jar throws; frontend diagnostics are
# emitted first and are the only thing this script judges on.
if grep -E '^[^ ]+\.kt:[0-9]+:[0-9]+: error:' "$OUTPUT"; then
  echo
  echo "FAILED — type errors above. Full output: $OUTPUT"
  exit 1
fi

echo "OK — no resolution or type errors in the verified Android sources."
