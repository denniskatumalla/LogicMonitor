#!/usr/bin/env bash
# Builds the jar, runs the Java suite (JDK only), then the collection-script
# suite (needs groovy on PATH; skipped with a notice if absent). Offline.
set -euo pipefail
# Run from the repository root.
cd "$(dirname "${BASH_SOURCE[0]}")"
# Build first: the tests need the compiled classes and the packaged jar.
./build.sh
out=build/test-classes
# Compile the tests against the service classes (serialization warnings ignored).
rm -rf "$out" && mkdir -p "$out"
javac --release 21 -Xlint:all,-serial -d "$out" -cp build/classes $(find src/test/java -name '*.java')

echo "== Java suite"
# TestRunner is the project's own small harness (no JUnit); outage.jar tells it where the jar is.
java -cp build/classes:"$out" -Doutage.jar=build/outage-reporter.jar outage.TestRunner

echo "== Collection script suite"
if command -v groovy > /dev/null; then
  # The in-process servers log to stdout; keep the report readable.
  groovy -cp build/classes src/test/groovy/CollectionScriptTest.groovy | grep -v "^ts="
else
  echo "groovy not found: skipped (install Groovy to test scripts/collection.groovy)"
fi
