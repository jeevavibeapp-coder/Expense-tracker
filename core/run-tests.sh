#!/bin/bash
# Compile and run the domain-core tests on a plain JVM.
#
# The domain logic deliberately has no Android dependency, so it can be
# verified without the Android SDK — which matters because the SDK is not
# always reachable, and because a two-second feedback loop on the parser is
# worth more than a five-minute one on an APK.
set -euo pipefail
cd "$(dirname "$0")"

KH=${KOTLIN_HOME:-/opt/kotlin}
STDLIB="$KH/kotlin-stdlib.jar"
if [ ! -f "$STDLIB" ]; then
  echo "Kotlin stdlib not found at $STDLIB." >&2
  echo "Set KOTLIN_HOME, or use Gradle: ./gradlew :core:test" >&2
  exit 2
fi
KOTLINC=${KOTLINC:-kotlinc-jvm}

# Every run builds in its own private directory. This used to compile
# straight into build/classes after an rm -rf, so two runs at once — normal
# when several agents verify their work in parallel — deleted each other's
# classes mid-run and every suite failed with NoClassDefFoundError, which reads
# exactly like a regression and is not one.
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

$KOTLINC -cp "$STDLIB" -nowarn -d "$WORK/classes" $(find src/main/kotlin -name '*.kt')
$KOTLINC -cp "$STDLIB:$WORK/classes" -nowarn -d "$WORK/test" $(find src/test/kotlin -name '*.kt')

status=0
for main in $(cd "$WORK/test" && ls *Kt.class 2>/dev/null | sed 's/\.class$//'); do
  echo "── $main ──"
  java -cp "$WORK/classes:$WORK/test:$STDLIB" "$main" || status=1
done

# Publish the compiled :core for anyone who wants it, by swapping a complete
# directory into place rather than writing into the live one, so a reader
# never sees a half-written tree. Nothing in tools/ depends on this any more.
mkdir -p build
cp -r "$WORK/classes" "build/classes.new.$$"
rm -rf "build/classes.old.$$"
[ -d build/classes ] && mv build/classes "build/classes.old.$$"
mv "build/classes.new.$$" build/classes
rm -rf "build/classes.old.$$"
exit $status
