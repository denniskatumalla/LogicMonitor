#!/usr/bin/env bash
# Copies build/shortlink.jar to the VM and (re)starts the service and load generator.
#   deploy/push.sh ec2-user@<public-dns> [path/to/key.pem]
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
target=${1:?usage: deploy/push.sh ec2-user@HOST [key.pem]}
ssh_opts=(-o StrictHostKeyChecking=accept-new)
[[ $# -ge 2 ]] && ssh_opts+=(-i "$2")

[[ -f build/shortlink.jar ]] || ./build.sh
scp "${ssh_opts[@]}" build/shortlink.jar "${target}:/tmp/shortlink.jar"
ssh "${ssh_opts[@]}" "$target" 'set -e
  sudo cloud-init status --wait > /dev/null
  sudo install -o root -g root -m 0644 /tmp/shortlink.jar /opt/shortlink/shortlink.jar
  sudo systemctl restart shortlink
  sudo systemctl restart shortlink-loadgen
  for i in $(seq 1 30); do curl -sf http://127.0.0.1:8080/health && echo && exit 0; sleep 1; done
  echo "shortlink did not come up; see: journalctl -u shortlink -n 50" >&2; exit 1'
