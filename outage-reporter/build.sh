#!/usr/bin/env bash
# Compiles the service for Java 21 and packages build/outage-reporter.jar,
# with the web pages from src/main/resources inside it.
# Needs only a JDK 21+ (javac, jar); no Maven, Gradle or third-party jars.
set -euo pipefail
# Run from the repository root, wherever the script is called from.
cd "$(dirname "${BASH_SOURCE[0]}")"
out=build/classes
# Start from an empty classes directory so deleted sources leave nothing behind.
rm -rf "$out" build/outage-reporter.jar && mkdir -p "$out"
# Compile every source file for the Java 21 runtime with debug info; any warning fails the build.
javac --release 21 -g -Xlint:all -Werror -d "$out" $(find src/main/java -name '*.java')
# Copy the web pages and other resources next to the classes so they go into the jar.
cp -R src/main/resources/. "$out"/
# Package with outage.Main as the entry point, so the VM can start it with java -jar.
jar --create --file build/outage-reporter.jar --main-class outage.Main -C "$out" .
echo "built build/outage-reporter.jar ($(wc -c < build/outage-reporter.jar) bytes)"
