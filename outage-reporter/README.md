# Outage Reporter

A small utility outage-reporting service in plain Java, built to be
monitored. Customers of **Example Power & Light**, a fictional electric
utility, use it to report a power outage, track their ticket to restoration
and see open outages per service area. It runs on one EC2 instance next to a
LogicMonitor collector. The exercise is to show LogicMonitor watching the VM,
the OS and the application, and what happens when something breaks.

It continues a customer story: an electric utility that had to keep
customer-facing outage reporting reliable through hurricane season. This is a
small, generic version of that kind of service. The utility, its service
areas, ZIP codes and phone numbers are all made up.

The service fails in the ways the real thing fails, and each failure maps to
one datapoint and one alert:
- **Technical faults:** slow responses, failing requests, a store that can't
  take writes.
- **A business fault:** a storm surge of reports beyond what the dispatchers
  can triage. Every technical datapoint stays green.

- **[docs/DOCUMENTATION.md](docs/DOCUMENTATION.md)**: architecture, the web UI
  and API, deployment, how each LogicMonitor layer is set up, operations and
  troubleshooting.
- **Code design** ([§2.3–2.5](docs/DOCUMENTATION.md#23-application-architecture)):
  the application architecture diagram, the object model with what each
  class does, the ticket lifecycle, and sequence diagrams of the method calls
  for startup, a customer report, a health poll, the dispatcher and a storm.

## Contract

```
Service:       Outage Reporter 1.0.0, Java 21+, zero third-party dependencies, one jar
Public:        :8080  GET / · /status · /areas · /monitoring   customer web pages (no CDN, no external fetches)
                      POST /report                       form POST, works without JavaScript
                      POST /api/reports · GET /api/reports/{id} · GET /api/zip/{zip} · GET /api/areas
                      GET /health
               :8443  same, over TLS (self-signed, 25 days, generated at install)
Admin:         127.0.0.1:8081  POST /admin/chaos?mode=latency|errors|store|storm|off  (X-Admin-Token)
JMX:           <private-ip>:9010, MBean outagereporter:type=Stats, plus the standard JVM MBeans
Logs:          key=value lines on stdout → journald → rsyslog → collector UDP 514 → LM Logs
Module:        Outage_Reporter_Health (Groovy script DataSource, 60 s)
AppliesTo:     outage.port =~ "^[0-9]+$"
Technical:     reachable, httpStatus, responseTimeMs, status, uptimeSeconds, requestRate,
               serverErrorRate, clientErrorRate (derive, min 0), p95LatencyMs, errorRatePct,
               storeOk, chaosMode
Business:      reportsLastMinute, failedReportsLastMinute, openOutages, customersAffected,
               awaitingTriage, oldestOpenMinutes, overdueOutages, stormMode
```

## The domain

A ticket moves through four statuses. A background **dispatcher** moves it,
standing in for the utility's outage management system and its people. Real
restorations take hours; here they are compressed into minutes so a whole
ticket life fits in a demo.

| Status | When | What changes |
|---|---|---|
| `reported` | The customer submits | Ticket `EPL-XXXXXX` (no 0/O or 1/I: these get read out over the phone) |
| `confirmed` | Triage: oldest first, at least 10 s after the report, **at most 180 a minute** | Customers affected (one transformer, a lateral, sometimes a whole feeder) and the first ETR: 6–10 min, doubled under storm response |
| `crew_assigned` | A quarter of the way to the ETR | One ETR in four slips by 25–50 % ("revised") |
| `restored` | At the ETR | Closed |

Triage capacity is the deliberate bottleneck. Reports that arrive faster
than 180 a minute queue up as **`awaitingTriage`**. Those are customers who
reported and have no ETR yet, which is the business number a storm puts at
risk.

Six fictional service areas cover 3,000,000 customers in ZIP codes
00010–00069. No real ZIP code is that low, so none of these is a real
place. ZIP **00099** is reserved for synthetic monitoring. Those reports are
stored like any other, so a check exercises the whole write path. They are
then closed at once and left out of every business figure.

## Health model

`/health` returns one of three states. The rules are in `Health.classify`:

| Status | When | HTTP |
|---|---|---|
| `DOWN` | The store cannot take writes, so reports would be lost | 503 |
| `DEGRADED` | p95 latency over the last 60 s is above 500 ms, or more than 5 % of requests failed with 5xx (once there are at least 10 requests in the window) | 200 |
| `UP` | Otherwise | 200 |

**A storm never changes the status.** A busy service that answers quickly is
healthy, and the business datapoints say the rest. That separation is the
point of the storm demo.

```json
{"status":"UP","uptimeSeconds":5412,"requestsTotal":6290,"errorsTotal":0,"clientErrorsTotal":118,
 "windowSeconds":60,"windowRequests":74,"p95LatencyMs":3.1,"errorRatePct":0.0,"storeOk":true,
 "chaosMode":"off","tickets":1840,"reportsTotal":402,"reportsLastMinute":6,"failedReportsLastMinute":0,
 "openOutages":41,"customersAffected":2310,"awaitingTriage":1,"oldestOpenMinutes":13.2,
 "overdueOutages":0,"stormMode":false,"version":"1.0.0"}
```

`/health`'s own requests are left out of the counters. Otherwise the
collector's polling would inflate the request rate and dilute the error rate.

## Datapoint choices

| Datapoint | Type | Threshold | Why |
|---|---|---|---|
| `reachable` | gauge | `!= 1` critical | The service is gone, so nobody can report online. This is the only failure where the script can't read anything else |
| `storeOk` | gauge | `!= 1` critical | Can't take reports. The direct cause of `DOWN` |
| `p95LatencyMs` | gauge | `> 500` warn, `> 1000` error | Customers are watching a spinner. The warning matches the app's own DEGRADED rule |
| `errorRatePct` | gauge | `> 5` warn, `> 20` error | Customers are getting errors. The warning matches the app's own DEGRADED rule |
| `responseTimeMs` | gauge | `> 2000` warn | `/health` itself is slow, which usually means the JVM or host is starved |
| **`awaitingTriage`** | gauge | **`> 60` warn, `> 150` error** | The storm alert. The service is healthy, but reports arrive faster than they can be confirmed (see below) |
| **`oldestOpenMinutes`** | gauge | **`> 60` warn** | Something has stalled. 60 minutes is about twice the longest restoration the model expects, even under storm response |
| `requestRate`, `serverErrorRate`, `clientErrorRate` | **derive, min 0** | none | Rates from lifetime counters (see below) |
| `reportsLastMinute`, `openOutages`, `customersAffected`, `failedReportsLastMinute`, `overdueOutages`, `stormMode` | gauge | none | The business view for dashboards. Each is either context or the business face of a technical alert |
| `status`, `httpStatus`, `uptimeSeconds`, `chaosMode` | gauge | none | Context for dashboards and alert triage |

### Why `awaitingTriage`, and why 60 and 150

Report volume alone isn't the problem. In a storm, volume is expected, and
alerting on it tells the on-call team nothing it can act on. The problem is
**volume beyond triage capacity**: then customers who have already reported
wait without an estimated restoration time, and they call. So the alert
watches the queue, not the intake. That also means it clears when the queue
is worked off, not when the wind drops, just like the real thing.

The thresholds are sized to triage capacity, 180 a minute (3 a second):

- **Normal** is 1–5. That's just reports inside their 10-second triage delay.
- **60, the warning**, is about 20 s of triage capacity behind. It's well
  clear of normal, and it is the earliest sign that intake has outrun
  triage.
- **150, the error**, is about 50 s behind and still growing. At the demo's
  surge rate of 6 a second, the queue grows by 3 a second, so the error
  comes about 45 s into a storm. In production you'd set these in minutes
  of capacity; the shape is the same.

The storm alert was measured on this service at default settings:
- Warning at about 17 s after `chaos storm`.
- Error at about 43 s.
- After `chaos off`, the queue drains at 3 a second, so the error clears
  once the backlog falls below 150 and the warning once it falls below 60.

**One fault, one alert.** `status` has no threshold, because it summarises
`storeOk`, `p95LatencyMs` and `errorRatePct`.
- `customersAffected`, `openOutages` and `reportsLastMinute` all jump in a
  storm. If they had thresholds too, one storm would page four times.
- `failedReportsLastMinute` is how many customers an `errors` or `store`
  fault turned away. That's the sentence the business wants, so it goes on
  the dashboard, not in a second alert.
- `CollectionScriptTest` runs each fault against a live service, applies the
  module's thresholds, and asserts exactly one alert on the datapoint that
  names the cause.

**Derive with a minimum of 0, not counter.** The counters reset to zero when
the service restarts. LogicMonitor's COUNTER type assumes a decrease means
the counter wrapped, and stores a huge spike. DERIVE stores the negative rate
instead. A valid range with a minimum of 0 rejects that one sample as No Data.

**Failures exit 0.** The script uses the same rule as
[lm-tls-cert-expiry](../lm-tls-cert-expiry/README.md#why-failures-exit-0):
a non-zero exit records no data, and no data never alerts. So an unreachable
service prints `reachable=0` and nothing else.

**A 503 is an answer, not an outage.** When the service reports DOWN, the
script still parses the body and reports `reachable=1, httpStatus=503,
storeOk=0`. The alert then says the store is broken, which is the real
problem.

## Fault injection

| Mode | Effect | What a customer sees | Expected alert |
|---|---|---|---|
| `latency` | Every dynamic request (`/api/*`, `POST /report`) sleeps 1500 ms | The page loads, then the button spins: "Sending your report…", then "This is taking longer than usual" | `p95LatencyMs` error |
| `errors` | 50 % of dynamic requests return 500 | About half of submissions show "Report not sent. We can't take your report right now — please try again in a few minutes or call 1-800-555-0142." | `errorRatePct` error |
| `store` | Store writes fail. Reports get 503, lookups still work, the dispatcher pauses, `/health` returns 503 DOWN | Every submission shows the same message | `storeOk` critical |
| `storm` | An in-process surge: reports ramp to 6 a second over 10 s, mostly on the two coastal areas | Storm banner on every page; open outages and customers out climb on the area view | `awaitingTriage` error |
| `systemctl stop outage-reporter` | Process gone | The browser can't connect | `reachable` critical, plus the web checks |

Pages and static assets are exempt from latency and errors, as if served
from a CDN. So the page always renders, and its own loading and error states
are what the audience sees.

The storm surge goes through the same service layer as a customer's report,
but not through HTTP. So request rate, latency and error rate stay flat, and
only the business datapoints move.

The admin listener binds to `127.0.0.1` and only starts when
`OUTAGE_ADMIN_TOKEN` is set. The security group never opens 8081, so chaos
is triggered from a shell on the VM (`sudo chaos storm`). `/admin/*` on the
public port returns 404.

## Build and test

Needs only a JDK, 21 or later (`javac`, `jar`). No Maven or Gradle, and no
third-party jars. Groovy is optional; it runs the collection-script tests.

```bash
./build.sh                 # build/outage-reporter.jar (compiled with --release 21, web pages inside)
./run-tests.sh             # 71 JDK tests + 9 collection-script tests, offline, ~15 s
deploy/make-user-data.sh   # build/user-data.sh for EC2 (14,011 bytes; the limit is 16,384)
```

| Suite | What it covers |
|---|---|
| `JsonTest`, `LogTest` | The hand-rolled JSON parser and writer, key=value log escaping |
| `MetricsTest` | Nearest-rank p95, window expiry, 4xx versus 5xx, ring wrap-around |
| `HealthTest` | UP / DEGRADED / DOWN rules, 503 only for DOWN, a storm is still UP |
| `ValidationTest` | Report rules and customer messages (ZIP, ZIP from the address, phone formats, lengths), form decoding, chaos modes, config |
| `TicketStoreTest` | Persistence of tickets and status changes across restart, a torn last line, nothing half-written on failure, recovery after a real I/O failure, 400 concurrent writers |
| `DomainTest` | KPIs, synthetic tickets excluded, storm declared on volume, age and overdue, customers capped per area, privacy of the ZIP view, the event window |
| `DispatcherTest` | Every status in order with an ETR, triage capacity and oldest-first, storm ETRs doubled, about one ETR in four slipping, a broken store pausing without losing work |
| `StormSurgeTest` | Ramp and hold, stop and fresh ramp, landfall on the coast, a broken store dropping the surge |
| `EndToEndTest` | Real servers on random ports: report, track, ZIP and area views, 4xx cases, the live dispatcher, every chaos mode and what it does to customers, admin auth, JMX, HTTPS, restart, 100 concurrent reports |
| `PagesTest` | Every page and asset with its type and CSP; nothing fetched from elsewhere; the no-JavaScript form POST; a bad form POST; the synthetic-check path end to end; the storm banner; HTML escaping |
| `ProcessTest` | The packaged jar with the documented JMX flags, read over **remote** JMX as the collector would; the pages are inside the jar; SIGTERM exits 143 after the shutdown hook |
| `CollectionScriptTest` (Groovy) | `scripts/collection.groovy` run the collector's way (`hostProps`, no `args`) against a live service. The module's thresholds are applied, so each of latency, errors, store, storm and stop gives exactly one alert |

The collection script avoids Groovy 3+ syntax. It was also run in podman
against a live instance and a closed port, under two runtimes:
- Groovy 2.4.16 on JDK 8.
- Groovy 4.0 on JDK 17. Current collectors run Groovy 4.

`build/user-data.sh` was also run end to end in an `amazonlinux:2023`
container, with `systemctl` stubbed. See
[DOCUMENTATION §10](docs/DOCUMENTATION.md#10-what-was-verified-and-how).

## Repository layout

```
src/main/java/outage/        The service (JDK only)
src/main/resources/outage/web/  The customer pages: HTML templates, app.css, app.js, icon.svg
src/test/java/outage/        JDK-only test suite and its tiny runner
src/test/groovy/             Collection-script tests (JUnit 4, bundled with Groovy)
scripts/collection.groovy    Outage_Reporter_Health collection script
module/                      Outage_Reporter_Health.json (DataSource spec), Outage_Reporter_JMX.json
deploy/                      systemd units, env template, loadgen, chaos and cert helpers,
                             rsyslog forwarding, cloud-init template, push.sh
docs/                        DOCUMENTATION.md
build.sh, run-tests.sh       Entry points (output in build/, git-ignored)
```

## Known limitations

- **A model, not an outage management system.** Each report is its own
  outage. A real OMS groups calls by the transformer or feeder they predict
  is out. Customer impact is a random draw, and time is compressed from hours
  to minutes.
- **One node, one file.** The store is an append-only log on local disk, with
  no compaction or replication. Every ticket stays in memory. That's about
  300 bytes each, roughly 10 MB a week at the load generator's rate.
- **The storm is generated in-process**, so it doesn't load HTTP. That's
  deliberate, so only the business datapoints move.
- **Storm response follows intake.** The banner goes off a minute after
  reports drop below 60 a minute, even if the triage queue is still draining.
  `awaitingTriage` keeps alerting until the queue is worked off.
- **After a long stop**, tickets that were waiting for triage get a fresh ETR
  when the service starts. `oldestOpenMinutes` may then show a warning for
  up to 10 minutes while they finish.
- **Counters reset on restart.** That's acceptable because LogicMonitor
  stores rates (derive, min 0), not totals.
- **No authentication on the public API, and no rate limiting.** It's a demo;
  the security group is the perimeter. Logs never include a customer's address
  or phone number, and lookups show only the last four digits of the phone.
- **JMX is unauthenticated.** It is reachable only on the VM; see
  [DOCUMENTATION §7.4](docs/DOCUMENTATION.md#74-hardening-jmx).
