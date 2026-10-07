#!/usr/bin/env bash
# validate.sh <demo_dir> -- check the YAML, then run `otelcol-contrib validate` on the exact
# config stack the demo uses (base + full + observability + extras), with the demo's pinned
# collector image. Docker or podman. No LM credentials needed (validate makes no network calls).
set -euo pipefail
DEMO_DIR="${1:?usage: validate.sh <opentelemetry-demo dir>}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RT="${RUNTIME:-$(command -v docker >/dev/null 2>&1 && echo docker || echo podman)}"

CFG="$DEMO_DIR/src/otel-collector"
FILES=(otelcol-config.yml otelcol-config-full.yml otelcol-config-observability.yml otelcol-config-extras.yml)

# 1) YAML syntax (python3 + PyYAML if present)
if python3 -c 'import yaml' 2>/dev/null; then
  python3 - "$CFG" "${FILES[@]}" "$HERE/config/compose.extras.yaml" <<'EOF'
import sys, yaml, os
base, files, extra = sys.argv[1], sys.argv[2:-1], sys.argv[-1]
for p in [os.path.join(base, f) for f in files] + [extra]:
    yaml.safe_load(open(p)); print(f"  yaml ok: {os.path.basename(p)}")
EOF
else
  echo "  (python3-yaml not installed, skipping YAML syntax check)"
fi

# 2) Collector semantic validation
IMAGE=$(grep -E '^COLLECTOR_CONTRIB_IMAGE=' "$DEMO_DIR/.env" | cut -d= -f2-)
echo "  image: $IMAGE"
MOUNTS=(); ARGS=()
for f in "${FILES[@]}"; do
  MOUNTS+=(-v "$CFG/$f:/etc/$f:ro,Z"); ARGS+=("--config=/etc/$f")
done
# Values the configs interpolate (normally provided by compose from .env).
ENVS=(-e OTEL_COLLECTOR_HOST=otel-collector -e OTEL_COLLECTOR_PORT_GRPC=4317 -e OTEL_COLLECTOR_PORT_HTTP=4318
      -e FRONTEND_PROXY_ADDR=frontend-proxy:8080 -e IMAGE_PROVIDER_HOST=image-provider -e IMAGE_PROVIDER_PORT=8081
      -e POSTGRES_HOST=postgresql -e POSTGRES_PORT=5432 -e POSTGRES_MONITORING_PASSWORD=x -e AD_PROMETHEUS_PORT=9465
      -e KAFKA_ADDR=kafka:9092 -e LOGICMONITOR_ACCOUNT=validate -e "LOGICMONITOR_BEARER_TOKEN=Bearer validate")
# host_metrics checks that root_path exists, so give it an empty /hostfs.
"$RT" run --rm --tmpfs /hostfs "${MOUNTS[@]}" "${ENVS[@]}" "$IMAGE" validate "${ARGS[@]}" --feature-gates=service.profilesSupport
echo "  collector validate: OK"
