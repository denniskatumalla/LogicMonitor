#!/usr/bin/env bash
# Builds build/user-data.sh: the cloud-init template with the deploy files
# inlined, so EC2 user data needs nothing else from this repository.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
out=../build/user-data.sh
mkdir -p ../build
awk '
  /^@@include .*@@$/ {
    path = $2; sub(/@@$/, "", path)
    while ((getline line < path) > 0) print line
    close(path)
    next
  }
  { print }
' cloud-init/user-data.template.sh > "$out"
chmod +x "$out"
bash -n "$out"
size=$(wc -c < "$out")
# EC2 caps user data at 16 KB before base64 encoding.
if (( size > 16384 )); then echo "user-data is ${size} bytes, over EC2's 16 KB limit" >&2; exit 1; fi
echo "built build/user-data.sh (${size} bytes)"
