#!/usr/bin/env python3
"""lm-smoke-test.py -- test LM credentials and endpoints WITHOUT the demo running.

Sends one log record to {portal}/rest/log/ingest and one OTLP/JSON span to
{portal}/rest/api/v1/traces with the same auth logic as the collector exporter
(LMv1 if LOGICMONITOR_ACCESS_ID/KEY are set, otherwise LOGICMONITOR_BEARER_TOKEN).
Python standard library only.

    set -a; . ./.env.override; set +a
    python3 scripts/lm-smoke-test.py [--host <system.hostname of an LM resource>] [--metrics]

--metrics also POSTs one OTLP/JSON gauge to /rest/api/v1/metrics, to check the
undocumented OTLP metrics endpoint that the --with-otlp-metrics option uses.

How to read the result: 2xx = auth and endpoint OK. 401 = bad credentials or wrong
token type. 403 = the token's role lacks ingest permission, or the feature is not
licensed. 404 = wrong portal name or the endpoint is not enabled.
"""
import argparse, base64, hashlib, hmac, json, os, socket, sys, time, urllib.request, urllib.error

def auth_header(method, resource_path, body):
    aid, akey = os.getenv("LOGICMONITOR_ACCESS_ID"), os.getenv("LOGICMONITOR_ACCESS_KEY")
    if aid and akey:
        epoch = str(int(time.time() * 1000))
        digest = hmac.new(akey.encode(), (method + epoch + body.decode() + resource_path).encode(), hashlib.sha256).hexdigest()
        return f"LMv1 {aid}:{base64.b64encode(digest.encode()).decode()}:{epoch}", "LMv1"
    tok = os.getenv("LOGICMONITOR_BEARER_TOKEN")
    if tok:
        return (tok if tok.startswith("Bearer ") else "Bearer " + tok), "Bearer"
    sys.exit("no credentials in env (LOGICMONITOR_ACCESS_ID/KEY or LOGICMONITOR_BEARER_TOKEN)")

def post(base, resource_path, payload, force_bearer=False):
    body = json.dumps(payload).encode()
    if force_bearer:
        hdr, kind = os.getenv("LOGICMONITOR_BEARER_TOKEN", ""), "Bearer"
    else:
        hdr, kind = auth_header("POST", resource_path, body)
    req = urllib.request.Request(base + resource_path, data=body, method="POST",
                                 headers={"Content-Type": "application/json", "Authorization": hdr,
                                          "User-Agent": "otel-demo-lm-smoke/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            code, text = r.status, r.read(500).decode(errors="replace")
    except urllib.error.HTTPError as e:
        code, text = e.code, e.read(500).decode(errors="replace")
    except Exception as e:  # DNS, TLS, timeout
        code, text = "ERR", repr(e)
    ok = isinstance(code, int) and 200 <= code < 300
    print(f"[{'OK ' if ok else 'FAIL'}] POST {resource_path} ({kind}) -> {code} {text.strip()[:300]}")
    return ok

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default=os.getenv("LM_HOST_NAME") or socket.getfqdn(),
                    help="system.hostname of an existing LM resource to map the test log to")
    ap.add_argument("--metrics", action="store_true")
    a = ap.parse_args()
    acct = os.getenv("LOGICMONITOR_ACCOUNT") or sys.exit("LOGICMONITOR_ACCOUNT not set")
    base = os.getenv("LM_BASE_URL") or f"https://{acct}.logicmonitor.com/rest"  # LM_BASE_URL: testing only
    now = time.time_ns()
    print(f"portal={base}  host={a.host}")

    ok = post(base, "/log/ingest", [{
        "msg": "otel-demo-lm smoke test log", "_lm.resourceId": {"system.hostname": a.host},
        "service.name": "lm-smoke-test", "timestamp": int(now / 1e6)}])

    tid, sid = os.urandom(16).hex(), os.urandom(8).hex()
    res = {"attributes": [
        {"key": "service.name", "value": {"stringValue": "lm-smoke-test"}},
        {"key": "service.namespace", "value": {"stringValue": "opentelemetry-demo"}},
        {"key": "host.name", "value": {"stringValue": a.host}}]}
    ok &= post(base, "/api/v1/traces", {"resourceSpans": [{"resource": res, "scopeSpans": [{"spans": [{
        "traceId": tid, "spanId": sid, "name": "smoke-test", "kind": 2,
        "startTimeUnixNano": str(now - 5_000_000), "endTimeUnixNano": str(now)}]}]}]})
    print(f"       traceId={tid} (search for it on the Traces page in a minute or two)")

    if a.metrics:
        if not os.getenv("LOGICMONITOR_BEARER_TOKEN"):
            print("[SKIP] --metrics needs LOGICMONITOR_BEARER_TOKEN (the collector's otlp_http path is bearer-only)")
        else:
            ok &= post(base, "/api/v1/metrics", {"resourceMetrics": [{"resource": res, "scopeMetrics": [{"metrics": [{
                "name": "otel_demo_lm.smoke", "gauge": {"dataPoints": [{"asDouble": 1.0, "timeUnixNano": str(now)}]}}]}]}]},
                force_bearer=True)
    sys.exit(0 if ok else 1)

if __name__ == "__main__":
    main()
