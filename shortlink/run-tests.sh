#!/usr/bin/env bash
# Builds the jar, runs the Java suite (JDK only), then the collection-script
# suite (needs groovy on PATH; skipped with a notice if absent). Offline.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
./build.sh
out=build/test-classes
rm -rf "$out" && mkdir -p "$out"
javac --release 21 -Xlint:all,-serial -d "$out" -cp build/classes $(find src/test/java -name '*.java')

echo "== Java suite"
java -cp build/classes:"$out" -Dshortlink.jar=build/shortlink.jar shortlink.TestRunner

echo "== Collection script suite"
if command -v groovy > /dev/null; then
  # The in-process servers log to stdout; keep the report readable.
  groovy -cp build/classes src/test/groovy/CollectionScriptTest.groovy | grep -v "^ts="
else
  echo "groovy not found: skipped (install Groovy to test scripts/collection.groovy)"
fi
