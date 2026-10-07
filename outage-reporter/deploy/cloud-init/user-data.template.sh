#!/bin/bash
# Outage Reporter demo host bootstrap for Amazon Linux 2023 (x86_64 or arm64).
# Paste build/user-data.sh (made by deploy/make-user-data.sh) into EC2
# "Advanced details > User data". It runs once, as root, on first boot.
# Output: /var/log/cloud-init-output.log. Summary: /root/outage-reporter-lm-properties.txt
#
# Installs Java 21, the outage service user, systemd units, a self-signed
# keystore for the HTTPS listener, the load generator, net-snmp, rsyslog
# forwarding to the LM collector, and an unprivileged SSH user for LM's
# Linux SSH modules. The jar itself arrives later via deploy/push.sh.
set -euxo pipefail

TLS_VALIDITY_DAYS=25          # under 30 days, so TLS_Certificate_Expiry raises its warning
COLLECTOR_HOST=""             # empty = this VM's private IP (collector on the same VM)

imds() {
  local token
  token=$(curl -sf --connect-timeout 2 -X PUT http://169.254.169.254/latest/api/token \
    -H 'X-aws-ec2-metadata-token-ttl-seconds: 300') || return 1
  curl -sf --connect-timeout 2 -H "X-aws-ec2-metadata-token: ${token}" "http://169.254.169.254/latest/meta-data/$1"
}
secret() { head -c 18 /dev/urandom | base64 | tr -d '/+=' ; }

PRIVATE_IP=$(imds local-ipv4 || hostname -I | awk '{print $1}')
[[ -n $PRIVATE_IP ]] || { echo "cannot determine this VM's private IP" >&2; exit 1; }
PUBLIC_DNS=$(imds public-hostname || true)
PUBLIC_IP=$(imds public-ipv4 || true)
INSTANCE_ID=$(imds instance-id || echo unknown)
REGION=$(imds placement/region || echo unknown)
COLLECTOR_HOST=${COLLECTOR_HOST:-$PRIVATE_IP}

# --- packages ---------------------------------------------------------------
dnf install -y java-21-amazon-corretto-headless rsyslog net-snmp net-snmp-utils jq
# The LM collector installer wants xxd.
dnf install -y xxd || dnf install -y vim-common || true

# A little swap so the collector install and the JVMs never meet the OOM killer.
if [[ ! -f /swapfile ]]; then
  dd if=/dev/zero of=/swapfile bs=1M count=1024 status=none
  chmod 0600 /swapfile && mkswap /swapfile
  echo '/swapfile none swap defaults 0 0' >> /etc/fstab
fi
swapon /swapfile 2>/dev/null || swapon --show | grep -q /swapfile || echo "warning: swap not enabled" >&2

# --- service user, directories, secrets -------------------------------------
id outage &>/dev/null || useradd --system --home-dir /opt/outage-reporter --shell /sbin/nologin outage
install -d -m 0755 /opt/outage-reporter
install -d -m 0750 -o root -g outage /etc/outage-reporter

ADMIN_TOKEN=$(secret)
TLS_PASSWORD=$(secret)
SNMP_COMMUNITY=lm$(secret | head -c 16)

# --- configuration ------------------------------------------------------------
cat > /etc/outage-reporter/outage-reporter.env <<EOF
OUTAGE_BIND=0.0.0.0
OUTAGE_PORT=8080
OUTAGE_ADMIN_BIND=127.0.0.1
OUTAGE_ADMIN_PORT=8081
OUTAGE_ADMIN_TOKEN=${ADMIN_TOKEN}
OUTAGE_TLS_PORT=8443
OUTAGE_TLS_KEYSTORE=/etc/outage-reporter/tls.p12
OUTAGE_TLS_PASSWORD=${TLS_PASSWORD}
OUTAGE_DATA_DIR=/var/lib/outage-reporter
JAVA_OPTS="-Xms128m -Xmx256m -XX:+ExitOnOutOfMemoryError"
JMX_OPTS="-Dcom.sun.management.jmxremote.port=9010 -Dcom.sun.management.jmxremote.rmi.port=9010 -Dcom.sun.management.jmxremote.host=${PRIVATE_IP} -Djava.rmi.server.hostname=${PRIVATE_IP} -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false"
EOF
chown root:outage /etc/outage-reporter/outage-reporter.env && chmod 0640 /etc/outage-reporter/outage-reporter.env

# Self-signed certificate for the HTTPS listener.
cat > /usr/local/sbin/outage-cert <<'EOF_SCRIPT'
@@include outage-cert.sh@@
EOF_SCRIPT
chmod 0755 /usr/local/sbin/outage-cert
/usr/local/sbin/outage-cert "$TLS_VALIDITY_DAYS"

cat > /etc/systemd/system/outage-reporter.service <<'EOF_UNIT'
@@include systemd/outage-reporter.service@@
EOF_UNIT

cat > /etc/systemd/system/outage-reporter-loadgen.service <<'EOF_UNIT'
@@include systemd/outage-reporter-loadgen.service@@
EOF_UNIT

cat > /opt/outage-reporter/loadgen.sh <<'EOF_SCRIPT'
@@include loadgen.sh@@
EOF_SCRIPT
chmod 0755 /opt/outage-reporter/loadgen.sh

cat > /usr/local/bin/chaos <<'EOF_SCRIPT'
@@include chaos.sh@@
EOF_SCRIPT
chmod 0755 /usr/local/bin/chaos

# --- logs: journald -> rsyslog -> LM collector (UDP 514) ---------------------
cat > /etc/rsyslog.d/60-outage-reporter-lm.conf <<'EOF_RSYSLOG'
@@include rsyslog/60-outage-reporter-lm.conf@@
EOF_RSYSLOG
sed -i "s/@COLLECTOR_HOST@/${COLLECTOR_HOST}/" /etc/rsyslog.d/60-outage-reporter-lm.conf
rsyslogd -N1

# --- SNMP for LM's Linux OS modules ------------------------------------------
# v2c, answering only this VM's own addresses: the collector runs here, and
# traffic to the VM's own IP never leaves the host, so the security group
# needn't open 161. Use SNMPv3 when the collector is elsewhere.
[[ -f /etc/snmp/snmpd.conf.orig ]] || cp /etc/snmp/snmpd.conf /etc/snmp/snmpd.conf.orig
cat > /etc/snmp/snmpd.conf <<EOF
agentaddress udp:127.0.0.1:161,udp:${PRIVATE_IP}:161
rocommunity ${SNMP_COMMUNITY} 127.0.0.1
rocommunity ${SNMP_COMMUNITY} ${PRIVATE_IP}/32
syslocation "AWS ${REGION} ${INSTANCE_ID}"
syscontact "Outage Reporter demo"
EOF
chmod 0600 /etc/snmp/snmpd.conf

# --- unprivileged SSH user for LM's Linux SSH modules (e.g. Service Status) --
id lmmonitor &>/dev/null || useradd --create-home --shell /bin/bash lmmonitor
install -d -m 0700 /etc/lm-ssh
[[ -f /etc/lm-ssh/lmmonitor_id_rsa ]] || ssh-keygen -q -m PEM -t rsa -b 3072 -N '' -C lm-collector -f /etc/lm-ssh/lmmonitor_id_rsa
install -d -m 0700 -o lmmonitor -g lmmonitor /home/lmmonitor/.ssh
install -m 0600 -o lmmonitor -g lmmonitor /etc/lm-ssh/lmmonitor_id_rsa.pub /home/lmmonitor/.ssh/authorized_keys

# --- start what can start now --------------------------------------------------
systemctl daemon-reload
systemctl enable --now rsyslog snmpd
systemctl enable outage-reporter outage-reporter-loadgen   # start after push.sh delivers the jar

cat > /root/outage-reporter-lm-properties.txt <<EOF
# Outage Reporter demo host ${INSTANCE_ID} (${REGION})
# Private IP ${PRIVATE_IP}   Public ${PUBLIC_DNS:-none} ${PUBLIC_IP:-}
#
# Set these on the EC2 resource in LogicMonitor (Resources > the instance > Properties):
outage.port=8080
jmx.port=9010
snmp.version=v2c
snmp.community=${SNMP_COMMUNITY}
ssh.user=lmmonitor
ssh.cert=/etc/lm-ssh/lmmonitor_id_rsa
tls.endpoints=${PUBLIC_DNS:-localhost}:8443
linux.ssh.services=outage-reporter.service
#
# Web checks: page http://${PUBLIC_DNS:-<public-dns>}:8080/  form POST /report with zip=00099 (synthetic ZIP)
# Not for LogicMonitor: chaos admin token is in /etc/outage-reporter/outage-reporter.env (use: sudo chaos latency)
# Logs forward to ${COLLECTOR_HOST}:514/udp once the collector is installed.
EOF
chmod 0600 /root/outage-reporter-lm-properties.txt
echo "outage-reporter bootstrap complete"
