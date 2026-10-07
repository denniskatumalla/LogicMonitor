#!/usr/bin/env bash
# Demo helper on the VM: chaos latency|errors|store|storm|off, or no argument to show the mode.
# Reads the admin token and port from the service's env file (needs sudo).
set -euo pipefail
env_file=/etc/outage-reporter/outage-reporter.env
token=$(sed -n 's/^OUTAGE_ADMIN_TOKEN=//p' "$env_file")
port=$(sed -n 's/^OUTAGE_ADMIN_PORT=//p' "$env_file")
url="http://127.0.0.1:${port:-8081}/admin/chaos"
if [[ $# -eq 0 ]]; then
  curl -s -H "X-Admin-Token: ${token}" "$url"
else
  curl -s -X POST -H "X-Admin-Token: ${token}" "${url}?mode=$1"
fi
echo
