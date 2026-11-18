#!/bin/bash
# Rebuild everything the verification gates need, on a fresh machine.
#
# The cloud container this project is developed in is ephemeral. Each time it
# was recreated, the Kotlin toolchain and the Python test dependencies vanished
# with it, and nothing in the repo said how to get them back — the install
# steps lived only in a shell history that no longer existed. Until they were
# rebuilt, neither core/run-tests.sh nor tools/typecheck.sh could run, which
# meant no change could be verified at all.
#
# Idempotent: re-running it skips anything already present and valid.
#
#   ./tools/bootstrap.sh            # toolchain + python deps, then a smoke test
#   KOTLIN_HOME=/x BIN_DIR=/y ./tools/bootstrap.sh
set -euo pipefail
cd "$(dirname "$0")/.."

KOTLIN_HOME=${KOTLIN_HOME:-/opt/kotlin}
BIN_DIR=${BIN_DIR:-/usr/local/bin}
# Maven Central is the only content this needs. The Kotlin distribution zip
# and the Android SDK both come from hosts that have been blocked by egress
# policy in this environment; the bare compiler jars from Maven have not.
#
# Several mirrors of the same repository, tried in order, because one host is
# a single point of failure: repo1.maven.org has rate-limited this machine's
# egress IP with HTTP 429 while the Google and Apache mirrors served the same
# files without complaint.
MIRRORS=(
  https://maven-central.storage-download.googleapis.com/maven2
  https://repo.maven.apache.org/maven2
  https://repo1.maven.org/maven2
)
JARS=(
  "kotlin-compiler.jar|org/jetbrains/kotlin/kotlin-compiler/2.1.0/kotlin-compiler-2.1.0.jar"
  "kotlin-stdlib.jar|org/jetbrains/kotlin/kotlin-stdlib/2.1.0/kotlin-stdlib-2.1.0.jar"
  "kotlin-reflect.jar|org/jetbrains/kotlin/kotlin-reflect/2.1.0/kotlin-reflect-2.1.0.jar"
  "kotlin-test.jar|org/jetbrains/kotlin/kotlin-test/2.1.0/kotlin-test-2.1.0.jar"
  "coroutines.jar|org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.1/kotlinx-coroutines-core-jvm-1.8.1.jar"
  "trove4j.jar|org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar"
  "annotations.jar|org/jetbrains/annotations/23.0.0/annotations-23.0.0.jar"
)

is_jar() {
  # A jar is a zip, and every zip starts with "PK". Checking the magic catches
  # the failure that matters: an HTML error page saved under a .jar name.
  [ -s "$1" ] && [ "$(head -c 2 "$1")" = "PK" ]
}

echo "── kotlin toolchain → $KOTLIN_HOME"
mkdir -p "$KOTLIN_HOME"
for entry in "${JARS[@]}"; do
  name=${entry%%|*}
  path=${entry#*|}
  dest="$KOTLIN_HOME/$name"
  if is_jar "$dest"; then
    echo "   ok      $name"
    continue
  fi
  got=""
  for mirror in "${MIRRORS[@]}"; do
    # -f is what makes a 404 or 429 an error. Without it curl exits 0 and
    # writes the server's error page into the jar, and the failure surfaces
    # later as an unrelated-looking "invalid zip" from the compiler.
    if curl -fsSL --max-time 300 -o "$dest.part" "$mirror/$path" 2>/dev/null \
        && is_jar "$dest.part"; then
      got=$mirror
      break
    fi
    rm -f "$dest.part"
  done
  if [ -z "$got" ]; then
    echo "   FAILED  $name — no mirror served a valid jar" >&2
    exit 1
  fi
  mv "$dest.part" "$dest"
  echo "   fetched $name  (${got#https://})"
done

cat > "$BIN_DIR/kotlinc-jvm" <<WRAPPER
#!/bin/bash
# Drives the Kotlin compiler jar directly; see tools/bootstrap.sh for why.
KH=$KOTLIN_HOME
CP="\$KH/kotlin-compiler.jar:\$KH/coroutines.jar:\$KH/trove4j.jar:\$KH/kotlin-stdlib.jar:\$KH/kotlin-reflect.jar:\$KH/annotations.jar"
exec java -cp "\$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib "\$@"
WRAPPER
chmod +x "$BIN_DIR/kotlinc-jvm"
echo "   wrote   $BIN_DIR/kotlinc-jvm"

echo "── python test dependencies"
if python3 -c "import flask, pytest, hypothesis, waitress" 2>/dev/null; then
  echo "   ok      already installed"
else
  # Debian ships blinker system-wide without pip's RECORD file, so pip
  # cannot uninstall it to satisfy Flask's newer pin and the whole install
  # aborts. Installing blinker alone with --ignore-installed puts pip's own
  # copy in front of Debian's without touching the system package; everything
  # else then installs normally.
  python3 -m pip install -q --ignore-installed blinker
  python3 -m pip install -q -r python_app/requirements-dev.txt
  echo "   installed from python_app/requirements-dev.txt"
fi

# Prove it rather than assume it: compile and run a real program. A wrapper
# that exists but cannot compile would otherwise only be discovered by
# whoever runs the gates next, as a confusing failure in their work.
echo "── smoke test"
probe=$(mktemp -d)
trap 'rm -rf "$probe"' EXIT
printf 'fun main() { println("toolchain ok") }\n' > "$probe/Probe.kt"
kotlinc-jvm -cp "$KOTLIN_HOME/kotlin-stdlib.jar" -nowarn -d "$probe/out" "$probe/Probe.kt" \
  2>&1 | grep -v JAVA_TOOL_OPTIONS || true
out=$(java -cp "$probe/out:$KOTLIN_HOME/kotlin-stdlib.jar" ProbeKt 2>&1 | grep -v JAVA_TOOL_OPTIONS)
if [ "$out" != "toolchain ok" ]; then
  echo "   FAILED  the compiler did not produce a runnable program" >&2
  echo "$out" >&2
  exit 1
fi
echo "   $out"
echo
echo "Ready. Gates:"
echo "   ./core/run-tests.sh"
echo "   ./tools/typecheck.sh"
echo "   (cd python_app && python3 -m pytest tests -q)"
