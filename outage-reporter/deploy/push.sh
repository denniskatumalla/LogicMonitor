#!/usr/bin/env bash
# Copies build/outage-reporter.jar to the VM and (re)starts the service and load generator.
#   deploy/push.sh ec2-user@<public-dns> [path/to/key.pem]
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
target=${1:?usage: deploy/push.sh ec2-user@HOST [key.pem]}
ssh_opts=(-o StrictHostKeyChecking=accept-new)
[[ $# -ge 2 ]] && ssh_opts+=(-i "$2")

[[ -f build/outage-reporter.jar ]] || ./build.sh
scp "${ssh_opts[@]}" build/outage-reporter.jar "${target}:/tmp/outage-reporter.jar"
ssh "${ssh_opts[@]}" "$target" 'set -e
  sudo cloud-init status --wait > /dev/null
  sudo install -o root -g root -m 0644 /tmp/outage-reporter.jar /opt/outage-reporter/outage-reporter.jar
  sudo systemctl restart outage-reporter
  sudo systemctl restart outage-reporter-loadgen
  for i in $(seq 1 30); do curl -sf http://127.0.0.1:8080/health && echo && exit 0; sleep 1; done
  echo "outage-reporter did not come up; see: journalctl -u outage-reporter -n 50" >&2; exit 1'
