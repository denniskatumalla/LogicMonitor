#!/usr/bin/env bash
# Compiles the service for Java 21 and packages build/shortlink.jar.
# Needs only a JDK 21+ (javac, jar); no Maven, Gradle or third-party jars.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
out=build/classes
rm -rf "$out" build/shortlink.jar && mkdir -p "$out"
javac --release 21 -Xlint:all -Werror -d "$out" $(find src/main/java -name '*.java')
jar --create --file build/shortlink.jar --main-class shortlink.Main -C "$out" .
echo "built build/shortlink.jar ($(wc -c < build/shortlink.jar) bytes)"
