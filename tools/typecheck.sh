#!/bin/bash
# Type-check the Android sources without the Android SDK.
#
# Gradle cannot run here: the SDK is not installed and dl.google.com is
# unreachable. kotlinc can, so the real app sources are compiled against the
# hand-written declarations in tools/compose-stubs — see that directory's
# 00-README.md for what this does and does not prove.
#
# Usage:  tools/typecheck.sh [-v] [file.kt ...]
#   no args   every Android source, compiled as one unit
#   files     only those, still compiled with the stubs and :core
#   -v        print the full error text as well as the per-file summary
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD

KH=${KOTLIN_HOME:-/opt/kotlin}
KOTLINC=${KOTLINC:-kotlinc-jvm}
STUBS=$ROOT/tools/compose-stubs
APP=$ROOT/android/nativeapp/src/main/java
CORE_CLASSES=$ROOT/core/build/classes

VERBOSE=0
if [ "${1:-}" = "-v" ]; then VERBOSE=1; shift; fi

for jar in "$KH/kotlin-stdlib.jar" "$KH/coroutines.jar"; do
  [ -f "$jar" ] || { echo "missing $jar — set KOTLIN_HOME" >&2; exit 2; }
done

# :core is a dependency, not part of this check. Its own tests are the gate.
if [ ! -d "$CORE_CLASSES" ]; then
  echo "core/build/classes missing — building :core first" >&2
  (cd "$ROOT/core" && ./run-tests.sh >/dev/null) || {
    echo "core build failed; fix :core before type-checking the app" >&2; exit 2; }
fi

if [ "$#" -gt 0 ]; then SOURCES=("$@")
else mapfile -t SOURCES < <(find "$APP" -name '*.kt' | sort); fi

mapfile -t STUB_SOURCES < <(find "$STUBS" -name '*.kt' | sort)

OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

# Everything in one invocation: the app files reference each other, so
# compiling them separately would report their own declarations as missing.
$KOTLINC -cp "$KH/kotlin-stdlib.jar:$KH/coroutines.jar:$CORE_CLASSES" \
    -nowarn -d "$OUT/classes" "${STUB_SOURCES[@]}" "${SOURCES[@]}" \
    > "$OUT/raw" 2>&1
grep -v JAVA_TOOL "$OUT/raw" | grep 'error:' > "$OUT/errors" || true

# A stub error means the harness is wrong, not the app. Reported separately
# so it can never be mistaken for a defect in the code under test.
grep    "^$STUBS/" "$OUT/errors" > "$OUT/harness" || true
grep -v "^$STUBS/" "$OUT/errors" > "$OUT/app"     || true

total=$(wc -l < "$OUT/app")
harness=$(wc -l < "$OUT/harness")

echo "── type-check: $(printf '%s\n' "${SOURCES[@]}" | wc -l) source files, ${#STUB_SOURCES[@]} stub files"
echo

if [ "$harness" -gt 0 ]; then
  echo "!! $harness error(s) INSIDE THE HARNESS — fix tools/compose-stubs, not the app:"
  sed "s|$ROOT/||" "$OUT/harness"
  echo
fi

if [ "$total" -eq 0 ]; then
  echo "no errors in $(printf '%s\n' "${SOURCES[@]}" | wc -l) app source files"
else
  echo "$total error(s):"
  echo
  sed "s|^$ROOT/||" "$OUT/app" | sed 's/:[0-9]*:[0-9]*: error:.*//' | sort | uniq -c |
    sort -rn | awk '{ printf "  %5d  %s\n", $1, $2 }'
  echo
  if [ "$VERBOSE" -eq 1 ]; then
    sed "s|^$ROOT/||" "$OUT/app"
  else
    echo "  (re-run with -v for the full list)"
  fi
fi

[ "$total" -eq 0 ] && [ "$harness" -eq 0 ]
