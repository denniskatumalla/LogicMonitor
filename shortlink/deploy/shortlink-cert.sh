#!/usr/bin/env bash
# (Re)generates the self-signed certificate for the HTTPS listener from this
# VM's current addresses, and points SHORTLINK_BASE_URL at the current public
# DNS name. Run after attaching an Elastic IP:  sudo shortlink-cert [validity-days]
set -euo pipefail
days=${1:-25}
env_file=/etc/shortlink/shortlink.env
keystore=/etc/shortlink/tls.p12

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

password=$(sed -n 's/^SHORTLINK_TLS_PASSWORD=//p' "$env_file")
keytool=$(dirname "$(readlink -f /usr/bin/java)")/keytool
rm -f "$keystore"
"$keytool" -genkeypair -alias shortlink -keyalg RSA -keysize 2048 -validity "$days" \
  -dname "CN=${public_dns:-shortlink}, O=Shortlink demo" -ext "SAN=${san}" \
  -keystore "$keystore" -storetype PKCS12 -storepass "$password"
chown root:shortlink "$keystore" && chmod 0640 "$keystore"

sed -i "s|^SHORTLINK_BASE_URL=.*|SHORTLINK_BASE_URL=${public_dns:+http://${public_dns}:8080}|" "$env_file"
if systemctl is-active --quiet shortlink; then systemctl restart shortlink; fi
echo "certificate valid ${days} days for ${san}"
