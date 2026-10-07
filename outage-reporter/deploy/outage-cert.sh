#!/usr/bin/env bash
# (Re)generates the self-signed certificate for the HTTPS listener from this
# VM's current addresses. Run after attaching an Elastic IP:
#   sudo outage-cert [validity-days]
# 25 days by default: under the 30-day warning of TLS_Certificate_Expiry-.
set -euo pipefail
days=${1:-25}
env_file=/etc/outage-reporter/outage-reporter.env
keystore=/etc/outage-reporter/tls.p12

imds() {
  local token
  token=$(curl -sf --connect-timeout 2 -X PUT http://169.254.169.254/latest/api/token \
    -H 'X-aws-ec2-metadata-token-ttl-seconds: 300') || return 1
  curl -sf --connect-timeout 2 -H "X-aws-ec2-metadata-token: ${token}" "http://169.254.169.254/latest/meta-data/$1"
}
private_ip=$(imds local-ipv4 || hostname -I | awk '{print $1}')
public_dns=$(imds public-hostname || true)
public_ip=$(imds public-ipv4 || true)

san="dns:localhost,ip:127.0.0.1,ip:${private_ip}"
[[ -n $public_dns ]] && san="${san},dns:${public_dns}"
[[ -n $public_ip ]] && san="${san},ip:${public_ip}"

password=$(sed -n 's/^OUTAGE_TLS_PASSWORD=//p' "$env_file")
keytool=$(dirname "$(readlink -f /usr/bin/java)")/keytool
rm -f "$keystore"
"$keytool" -genkeypair -alias outage -keyalg RSA -keysize 2048 -validity "$days" \
  -dname "CN=${public_dns:-outage-reporter}, O=Example Power and Light demo" -ext "SAN=${san}" \
  -keystore "$keystore" -storetype PKCS12 -storepass "$password"
chown root:outage "$keystore" && chmod 0640 "$keystore"

if systemctl is-active --quiet outage-reporter; then systemctl restart outage-reporter; fi
echo "certificate valid ${days} days for ${san}"
