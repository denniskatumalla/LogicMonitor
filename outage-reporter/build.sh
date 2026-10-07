#!/usr/bin/env bash
# Compiles the service for Java 21 and packages build/outage-reporter.jar,
# with the web pages from src/main/resources inside it.
# Needs only a JDK 21+ (javac, jar); no Maven, Gradle or third-party jars.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
out=build/classes
rm -rf "$out" build/outage-reporter.jar && mkdir -p "$out"
javac --release 21 -g -Xlint:all -Werror -d "$out" $(find src/main/java -name '*.java')
cp -R src/main/resources/. "$out"/
jar --create --file build/outage-reporter.jar --main-class outage.Main -C "$out" .
echo "built build/outage-reporter.jar ($(wc -c < build/outage-reporter.jar) bytes)"
