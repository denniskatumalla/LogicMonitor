# Shortlink

A small URL shortener in plain Java, built to be monitored. It runs on one
EC2 instance next to a LogicMonitor collector, and the exercise is to show
LogicMonitor watching all three layers: the VM, the OS and the application.
It also has to show what happens when one of them breaks.

It is deliberately more than "hello world". A shortener does real work on
every request: it validates input, persists links, redirects and keeps
counts. That gives it real ways to fail, namely slow responses, failing
requests and a store it can no longer write to. Each failure maps to one
datapoint and one alert.

- **[docs/DOCUMENTATION.md](docs/DOCUMENTATION.md)**: architecture, API,
  deployment, how each LogicMonitor layer is set up, operations and
  troubleshooting.

## Contract

```
Service:       Shortlink 1.0.0, Java 21+, zero third-party dependencies
Public:        :8080  POST /api/links · GET /{code} · GET /api/links/{code} · GET /health
               :8443  same, over TLS (self-signed, generated at install)
Admin:         127.0.0.1:8081  POST /admin/chaos?mode=latency|errors|store|off  (X-Admin-Token)
JMX:           <private-ip>:9010, MBean shortlink:type=Stats, plus the standard JVM MBeans
Logs:          key=value lines on stdout → journald → rsyslog → collector UDP 514 → LM Logs
Module:        Shortlink_Health (Groovy script DataSource, 60 s)
AppliesTo:     shortlink.port =~ "^[0-9]+$"
Datapoints:    reachable, httpStatus, responseTimeMs, status, uptimeSeconds, links,
               requestRate, serverErrorRate, clientErrorRate (derive, min 0),
               p95LatencyMs, errorRatePct, storeOk, chaosMode
```

## Health model

`/health` returns one of three states. The rules are in `Health.classify`:

| Status | When | HTTP |
|---|---|---|
| `DOWN` | The store cannot take writes. New links would be lost, so the service cannot do its job | 503 |
| `DEGRADED` | p95 latency over the last 60 s is above 500 ms, or more than 5 % of requests failed with 5xx (once there are at least 10 requests in the window) | 200 |
| `UP` | Otherwise | 200 |

DEGRADED still returns 200 on purpose. A load balancer or uptime check should
keep sending traffic to a slow instance, because it is still serving.
Telling people it is slow is the monitoring system's job. Only DOWN, where the
instance genuinely can't do its job, returns 503.

```json
{"status":"UP","uptimeSeconds":5412,"links":118,"requestsTotal":6290,"errorsTotal":0,
 "clientErrorsTotal":311,"windowSeconds":60,"windowRequests":74,"p95LatencyMs":2.1,
 "errorRatePct":0.0,"storeOk":true,"chaosMode":"off","version":"1.0.0"}
```

`/health`'s own requests are left out of the counters. Otherwise the
collector's polling would inflate the request rate and dilute the error rate.

## Datapoint choices

| Datapoint | Type | Threshold | Why |
|---|---|---|---|
| `reachable` | gauge | `!= 1` critical | The service is gone. This is the only failure where the script can't read anything else |
| `storeOk` | gauge | `!= 1` critical | Can't create links. The direct cause of `DOWN` |
| `p95LatencyMs` | gauge | `> 500` warn, `> 1000` error | Users are waiting. The warning matches the app's own DEGRADED rule |
| `errorRatePct` | gauge | `> 5` warn, `> 20` error | Users are getting 500s. The warning matches the app's own DEGRADED rule |
| `responseTimeMs` | gauge | `> 2000` warn | `/health` itself is slow. This usually means the JVM or host is starved, not the app logic |
| `requestRate`, `serverErrorRate`, `clientErrorRate` | **derive, min 0** | none | Rates from lifetime counters (see below) |
| `status`, `httpStatus`, `uptimeSeconds`, `links`, `chaosMode` | gauge | none | Context for dashboards and alert triage |

**One fault, one alert.** `status` has no threshold. It summarises
`storeOk`, `p95LatencyMs` and `errorRatePct`, so alerting on it as well would
raise two alerts for one fault, and the second wouldn't say what was wrong.
`CollectionScriptTest` checks this. Each injected fault produces exactly one
alert, on the datapoint that names the cause.

**Derive with a minimum of 0, not counter.** The counters reset to zero when
the service restarts. LogicMonitor's COUNTER type assumes a decrease means
the counter wrapped, and stores a huge spike. DERIVE stores the negative rate
instead. A valid range with a minimum of 0 rejects that one sample as No Data,
so a restart shows as a one-poll gap, not a spike or a dip below zero.
LogicMonitor's own datapoint docs recommend this.

**Failures exit 0.** The script uses the same rule as
[lm-tls-cert-expiry](../lm-tls-cert-expiry/README.md#why-failures-exit-0). A
non-zero exit records no data, and no data never alerts. So an unreachable
service prints `reachable=0` and nothing else. The other datapoints go NaN
for that poll and raise nothing.

**A 503 is an answer, not an outage.** When the service reports DOWN, the
script still parses the body and reports `reachable=1, httpStatus=503,
storeOk=0`. The alert then says the store is broken, which is the actual
problem, rather than a vague "not reachable".

## Fault injection

| Mode | Effect | Expected alert |
|---|---|---|
| `latency` | Every public request (except `/health`) sleeps 1500 ms | `p95LatencyMs` error |
| `errors` | 50 % of public requests return 500 | `errorRatePct` error |
| `store` | Store writes fail. Creates return 503, redirects still work, `/health` returns 503 DOWN | `storeOk` critical |
| `systemctl stop shortlink` | Process gone | `reachable` critical, plus the website checks |

The admin listener binds to `127.0.0.1` and only starts when
`SHORTLINK_ADMIN_TOKEN` is set. The security group never opens 8081, so
chaos has to be triggered from a shell on the VM (`sudo chaos latency`).
`/admin/*` on the public port returns 404.

## Build and test

Needs only a JDK, 21 or later (`javac`, `jar`). No Maven or Gradle, and no
third-party jars. Groovy is optional; it runs the collection-script tests.

```bash
./build.sh          # build/shortlink.jar (compiled with --release 21)
./run-tests.sh      # 38 JDK tests + 8 collection-script tests, offline, ~10 s
deploy/make-user-data.sh   # build/user-data.sh for EC2
```

| Suite | What it covers |
|---|---|
| `JsonTest`, `LogTest` | The hand-rolled JSON parser and writer, key=value log escaping |
| `MetricsTest` | Nearest-rank p95, window expiry, 4xx versus 5xx, ring wrap-around |
| `HealthTest` | UP / DEGRADED / DOWN rules, the minimum-requests guard, 503 only for DOWN |
| `ValidationTest` | URL rules, query parsing, chaos modes, config from the environment |
| `LinkStoreTest` | Persistence across restart, a torn last line, no unpersisted links, recovery after a real I/O failure |
| `EndToEndTest` | Real servers on random ports: the CRUD flow, 4xx cases, every chaos mode and its recovery, admin auth, JMX, HTTPS, restart |
| `ProcessTest` | The packaged jar with the documented JMX flags, read over **remote** JMX as the collector would; SIGTERM exits 143 after the shutdown hook |
| `CollectionScriptTest` (Groovy) | `scripts/collection.groovy` run the collector's way (`hostProps`, no `args`) against a live service. The module's thresholds are then applied, so each fault gives exactly one alert |

The collection script avoids Groovy 3+ syntax. It was also run under Groovy
2.4.16 on JDK 8, both healthy and against a closed port. Current collectors
run Groovy 4 only (GD-39.004 and later), so this is just a safety margin.

`deploy/make-user-data.sh` output was run end to end in an `amazonlinux:2023`
container, with `systemctl` stubbed. Packages, keystore, env file, SNMP, SSH
user and rsyslog syntax (`rsyslogd -N1`) all worked. The app then ran from
that env file on Corretto 21 with JMX bound to the private IP.

## Repository layout

```
src/main/java/shortlink/   The service (11 types, JDK only)
src/test/java/shortlink/   JDK-only test suite and its tiny runner
src/test/groovy/           Collection-script tests (JUnit 4, bundled with Groovy)
scripts/collection.groovy  Shortlink_Health collection script
module/                    Shortlink_Health.json (DataSource spec), Shortlink_JMX.json
deploy/                    systemd units, env template, loadgen, chaos and cert helpers,
                           rsyslog forwarding, cloud-init template, push.sh
docs/                      DOCUMENTATION.md
build.sh, run-tests.sh     Entry points (output in build/, git-ignored)
```

## Known limitations

- **One node, one file.** The store is an append-only log on local disk with
  no compaction or replication. Each redirect appends a hit record, so the log
  grows by about 10 KB per thousand redirects.
- **Counters reset on restart.** That's acceptable because LogicMonitor
  stores rates (derive, min 0), not totals.
- **The p95 window is a 4096-sample ring.** Above about 68 requests per second
  it covers less than 60 s. That's fine for a health signal, but it is not a
  latency SLO.
- **No authentication on the public API, and no rate limiting.** It's a demo;
  the security group is the perimeter.
- **JMX is unauthenticated.** It is reachable only on the VM; see
  [DOCUMENTATION §7.4](docs/DOCUMENTATION.md#74-hardening-jmx) for the
  password-file variant.
