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

rm -rf build/classes build/test
$KOTLINC -cp "$STDLIB" -nowarn -d build/classes $(find src/main/kotlin -name '*.kt')
$KOTLINC -cp "$STDLIB:build/classes" -nowarn -d build/test $(find src/test/kotlin -name '*.kt')

status=0
for main in $(cd build/test && ls *Kt.class 2>/dev/null | sed 's/\.class$//'); do
  echo "── $main ──"
  java -cp "build/classes:build/test:$STDLIB" "$main" || status=1
done
exit $status
