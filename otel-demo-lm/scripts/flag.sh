#!/usr/bin/env bash
# flag.sh -- read or set OpenTelemetry Demo feature flags from the shell. Same effect as
# the UI at :8080/feature/. flagd watches src/flagd/demo.flagd.json and reloads it within seconds.
#
#   scripts/flag.sh list                       # every flag with its current default variant
#   scripts/flag.sh paymentFailure 50%         # turn on (variants: off 10% 25% 50% 75% 90% 100%)
#   scripts/flag.sh productCatalogFailure on
#   scripts/flag.sh reset                      # everything back to "off"
#
# DEMO_DIR defaults to ../opentelemetry-demo relative to this script.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
F="${DEMO_DIR:-$HERE/opentelemetry-demo}/src/flagd/demo.flagd.json"
[[ -f "$F" ]] || { echo "not found: $F" >&2; exit 1; }

python3 - "$F" "$@" <<'EOF'
import json, sys, os, tempfile
path, args = sys.argv[1], sys.argv[2:]
d = json.load(open(path))
flags = d["flags"]
def has_rule(v):
    r = v.get("targeting", {}).get("if")
    return isinstance(r, list) and len(r) == 3
def current(v):
    # For flags with a targeting rule (e.g. productCatalogFailure, only for product
    # OLJCESPC7Z), the flagd UI changes the rule's "matched" branch, not defaultVariant.
    return v["targeting"]["if"][1] if has_rule(v) else v["defaultVariant"]
def setv(v, variant):
    if has_rule(v): v["targeting"]["if"][1] = variant
    else: v["defaultVariant"] = variant
def show():
    for k, v in sorted(flags.items()):
        cur = current(v)
        mark = "" if cur == "off" else "   <-- ON"
        print(f"{k:32s} {cur:8s} variants={list(v['variants'])}{mark}")
if not args or args[0] == "list":
    show(); sys.exit(0)
if args[0] == "reset":
    for v in flags.values():
        if "off" in v["variants"]: setv(v, "off")
elif len(args) == 2:
    name, variant = args
    if name not in flags: sys.exit(f"unknown flag {name!r}; known: {', '.join(sorted(flags))}")
    if variant not in flags[name]["variants"]: sys.exit(f"unknown variant {variant!r}; choose from {list(flags[name]['variants'])}")
    setv(flags[name], variant)
else:
    sys.exit("usage: flag.sh list | reset | <flag> <variant>")
# Write-then-rename like flagd-ui does, so flagd never reads a half-written file
# (the whole src/flagd directory is bind-mounted).
fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), suffix=".tmp")
with os.fdopen(fd, "w") as fh:
    json.dump(d, fh, indent=2); fh.write("\n")
os.chmod(tmp, 0o644)
os.replace(tmp, path)
show()
EOF
