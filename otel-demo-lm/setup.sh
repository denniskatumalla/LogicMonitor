#!/usr/bin/env bash
# setup.sh -- clone the pinned OpenTelemetry Demo, add the LogicMonitor layer, and start it.
#
# Usage:
#   ./setup.sh [--with-otlp-metrics] [--with-cadvisor] [--minimal] [--no-start] [--validate-only]
#
#   --with-otlp-metrics  use config/otelcol-config-extras-with-metrics.yml (EXPERIMENTAL
#                        OTLP metrics to LM, bearer token required)
#   --with-cadvisor      also start cAdvisor (host port 8081) for LM's Docker LogicModule
#   --minimal            core services only, no Kafka/accounting/fraud-detection (~3 GB instead of ~6 GB).
#                        kafkaQueueProblems will not work in this mode.
#   --no-start           prepare files and validate only, do not start
#   --validate-only      only run the collector config validation on an existing checkout
#
# Env:
#   DEMO_DIR      checkout location (default: ./opentelemetry-demo next to this script)
#   DEMO_REF      git tag to pin (default: 3.1.0, released 2026-09-18)
#   COMPOSE       compose command override, e.g. "podman compose" (default: autodetect)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEMO_REF="${DEMO_REF:-3.1.0}"
DEMO_DIR="${DEMO_DIR:-$HERE/opentelemetry-demo}"
EXTRAS="$HERE/config/otelcol-config-extras.yml"
WITH_CADVISOR=0 MINIMAL=0 START=1 VALIDATE_ONLY=0

for a in "$@"; do
  case "$a" in
    --with-otlp-metrics) EXTRAS="$HERE/config/otelcol-config-extras-with-metrics.yml" ;;
    --with-cadvisor)     WITH_CADVISOR=1 ;;
    --minimal)           MINIMAL=1 ;;
    --no-start)          START=0 ;;
    --validate-only)     VALIDATE_ONLY=1 ;;
    -h|--help)           sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown option: $a" >&2; exit 2 ;;
  esac
done

log()  { printf '\n==> %s\n' "$*"; }
warn() { printf 'WARN: %s\n' "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- preflight
if [[ -z "${COMPOSE:-}" ]]; then
  if docker compose version >/dev/null 2>&1; then COMPOSE="docker compose"
  elif podman compose version >/dev/null 2>&1; then COMPOSE="podman compose"
  elif command -v podman-compose >/dev/null 2>&1; then COMPOSE="podman-compose"
  else die "no compose found. On the EC2 VM install Docker Engine + compose plugin (see README.md)."
  fi
fi
RUNTIME="${COMPOSE%% *}"; [[ "$RUNTIME" == podman-compose ]] && RUNTIME=podman
log "compose: $COMPOSE"

mem_kb=$(awk '/MemTotal/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)
mem_gb=$(( mem_kb / 1024 / 1024 ))
if (( MINIMAL == 0 && mem_kb < 7500000 )); then
  warn "only ${mem_gb} GiB RAM. The full demo needs ~6 GB (compose limits add up to ~7.7 GiB). Consider --minimal or adding swap."
fi

[[ -f "$HERE/.env.override" ]] || die "missing $HERE/.env.override. Run: cp .env.override.example .env.override and fill it in."

# Read the override file in a subshell so we can check it without exporting secrets.
# shellcheck disable=SC1091
check_env() (
  set +u; set -a; . "$HERE/.env.override"; set +a
  [[ -n "${LOGICMONITOR_ACCOUNT:-}" && "$LOGICMONITOR_ACCOUNT" != yourportal ]] || { echo "LOGICMONITOR_ACCOUNT not set"; exit 1; }
  [[ "$LOGICMONITOR_ACCOUNT" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "LOGICMONITOR_ACCOUNT must be the portal name only (no https://, no .logicmonitor.com)"; exit 1; }
  if [[ -n "${LOGICMONITOR_ACCESS_ID:-}" && -n "${LOGICMONITOR_ACCESS_KEY:-}" ]]; then echo "auth=LMv1"
  elif [[ -n "${LOGICMONITOR_BEARER_TOKEN:-}" ]]; then
    [[ "$LOGICMONITOR_BEARER_TOKEN" == "Bearer "* ]] || { echo "LOGICMONITOR_BEARER_TOKEN must start with 'Bearer '"; exit 1; }
    echo "auth=Bearer"
  else echo "no credentials: set LOGICMONITOR_ACCESS_ID/KEY or LOGICMONITOR_BEARER_TOKEN"; exit 1; fi
  if [[ "$EXTRAS" == *with-metrics* && -z "${LOGICMONITOR_BEARER_TOKEN:-}" ]]; then
    echo "--with-otlp-metrics needs LOGICMONITOR_BEARER_TOKEN (otlp_http cannot sign LMv1)"; exit 1; fi
)
msg=$(check_env) || die "$msg"
log ".env.override OK ($msg)"

# ---------------------------------------------------------------- clone
if (( VALIDATE_ONLY == 0 )); then
  if [[ -d "$DEMO_DIR/.git" ]]; then
    have=$(git -C "$DEMO_DIR" describe --tags --exact-match 2>/dev/null || echo "?")
    [[ "$have" == "$DEMO_REF" ]] || warn "$DEMO_DIR is at '$have', expected '$DEMO_REF'"
  else
    log "cloning opentelemetry-demo @ $DEMO_REF"
    git -c advice.detachedHead=false clone --depth 1 --branch "$DEMO_REF" \
      https://github.com/open-telemetry/opentelemetry-demo.git "$DEMO_DIR"
  fi

  log "installing LogicMonitor layer"
  # Keep the upstream stubs once so you can diff them or roll back.
  for f in src/otel-collector/otelcol-config-extras.yml compose.extras.yaml .env.override; do
    [[ -f "$DEMO_DIR/$f" && ! -f "$DEMO_DIR/$f.upstream" ]] && cp "$DEMO_DIR/$f" "$DEMO_DIR/$f.upstream"
  done
  cp "$EXTRAS"                        "$DEMO_DIR/src/otel-collector/otelcol-config-extras.yml"
  cp "$HERE/config/compose.extras.yaml" "$DEMO_DIR/compose.extras.yaml"
  cp "$HERE/.env.override"            "$DEMO_DIR/.env.override"
  chmod 600 "$DEMO_DIR/.env.override"

  # Fill in values the user left empty.
  if ! grep -qE '^LM_HOST_NAME=.+' "$DEMO_DIR/.env.override"; then
    h=$(hostname -f 2>/dev/null || hostname)
    [[ "$h" == localhost* ]] && warn "hostname is '$h'. Set LM_HOST_NAME in .env.override to the name the VM has in LM."
    sed -i '/^LM_HOST_NAME=/d' "$DEMO_DIR/.env.override"; echo "LM_HOST_NAME=$h" >> "$DEMO_DIR/.env.override"
    log "LM_HOST_NAME=$h (host.name stamped on all spans/logs)"
  fi
  if ! grep -qE '^PUBLIC_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=' "$DEMO_DIR/.env.override"; then
    # EC2 IMDSv2. Skipped quietly when not running on EC2.
    tok=$(curl -s -m 2 -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' 2>/dev/null || true)
    ip=$( [[ -n "$tok" ]] && curl -s -m 2 -H "X-aws-ec2-metadata-token: $tok" http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || true)
    if [[ "$ip" =~ ^[0-9.]+$ ]]; then
      echo "PUBLIC_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://$ip:8080/otlp-http/v1/traces" >> "$DEMO_DIR/.env.override"
      log "browser spans -> http://$ip:8080/otlp-http/v1/traces"
    fi
  fi
fi
[[ -d "$DEMO_DIR" ]] || die "$DEMO_DIR not found"

# ---------------------------------------------------------------- validate
log "validating merged collector config with the demo's own collector image"
"$HERE/scripts/validate.sh" "$DEMO_DIR" || die "collector config validation failed. NOT starting."
(( VALIDATE_ONLY == 1 )) && exit 0

# ---------------------------------------------------------------- start
cd "$DEMO_DIR"
FILES=(-f compose.yaml)
(( MINIMAL == 0 )) && FILES+=(-f compose.full.yaml)
FILES+=(-f compose.observability.yaml -f compose.extras.yaml)
ENVS=(--env-file .env --env-file .env.override)
(( WITH_CADVISOR == 1 )) && export COMPOSE_PROFILES=lm-cadvisor

echo
echo "Start command (save it for restarts):"
echo "  cd $DEMO_DIR && ${COMPOSE_PROFILES:+COMPOSE_PROFILES=$COMPOSE_PROFILES }$COMPOSE ${ENVS[*]} ${FILES[*]} up --force-recreate --remove-orphans --detach"
(( START == 0 )) && { log "--no-start: files are in place, nothing started"; exit 0; }

log "pulling images (first run: several GB, 5-10 min)"
$COMPOSE "${ENVS[@]}" "${FILES[@]}" pull --quiet || warn "pull had errors; compose up will retry/build"
log "starting"
$COMPOSE "${ENVS[@]}" "${FILES[@]}" up --force-recreate --remove-orphans --detach

cat <<EOF

OpenTelemetry Demo $DEMO_REF is starting (give it 2-3 minutes).
  Store         http://<host>:8080/
  Feature flags http://<host>:8080/feature/
  Load gen      http://<host>:8080/loadgen/
  Jaeger        http://<host>:8080/jaeger/ui/
  Grafana       http://<host>:8080/grafana/
Check the LM exporter:  $RUNTIME logs otel-collector 2>&1 | grep -iE 'logicmonitor|error' | tail
Then check that traces and logs arrive in LogicMonitor (see README.md).
EOF
