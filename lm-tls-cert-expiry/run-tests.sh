#!/usr/bin/env bash
# Compiles the simulator and tests, then runs the JUnit suite. Works offline.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
out=build/test-classes
rm -rf "$out" && mkdir -p "$out"
groovyc -d "$out" $(ls sim/*.groovy | grep -v '/lmsim.groovy$') tests/*.groovy
exec groovy -cp "$out" -e 'System.exit(AllTests.run() ? 0 : 1)'
