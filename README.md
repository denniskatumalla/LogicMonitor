# Outage Reporter, monitored end to end with LogicMonitor

A small but realistic customer-facing service, built to be monitored, and
the LogicMonitor setup that watches it from the cloud API down to the
business outcome.

## Why

An electric utility's outage-reporting site is the first thing customers hit
in a storm, and it fails in two ways: technically (slow pages, failed
requests, a broken data store) and as a business (reports arrive faster than
dispatchers can triage them, while every server metric stays green). This
project reproduces both on one AWS VM and shows how LogicMonitor catches each
one with a single alert that names its cause.

The utility, Example Power & Light, is fictional, and so are its service
areas, ZIP codes and phone numbers.

## Architecture

```
 Customers ──HTTP 8080──►┌──────────── EC2 VM (Amazon Linux 2023) ────────────┐
 LM Uptime (5 sites) ───►│  Outage Reporter (Java 21, JDK only)               │
                         │    /health JSON ── JMX :9010 ── stdout key=value   │
                         │         │              │              │            │
                         │         ▼              ▼              ▼ rsyslog    │
                         │  LogicMonitor Collector (SNMP, JMX, Groovy, syslog)│
                         └───────────────────────┬────────────────────────────┘
 AWS CloudWatch ── LM cloud collectors ──┐       │ outbound HTTPS 443 only
                                         ▼       ▼
                                  LogicMonitor portal: metrics, logs, alerts,
                                  executive and engineer dashboards
```

| Layer | What is watched | How |
|---|---|---|
| VM | Instance state, CPU, network, status checks | AWS integration (read-only IAM role with an External ID) |
| OS | CPU, memory, filesystems, interfaces | SNMP through the Collector |
| JVM | Heap, garbage collection, threads | JMX DataSource |
| Application | Reachable, data store, p95 latency, error rate | `Outage_Reporter_Health`, a Groovy DataSource reading `/health` |
| Business | Customers affected, open outages, customers awaiting an estimate | The same DataSource, the same poll |
| Customer's view | Load the site and file a report, from five continents | LM Uptime multi-step web check |
| Events | Requests, reports, ticket changes, health and chaos changes | Structured logs into LM Logs over syslog |

**Observability design:** each fault alerts on the one datapoint that names
its cause, and the rest are context, so one problem is one page. The
collection script exits 0 even when the service is down and reports
`reachable=0`, because a failing script records no data, and no data never
alerts. Errors and criticals route to an on-call escalation chain; warnings
stay on the dashboards.

**Stack:** Java 21 with only the JDK (built-in HTTP server, JMX, a
hand-rolled JSON codec), plain HTML/CSS/JavaScript, bash, cloud-init and
systemd on AWS EC2, Groovy for the LogicMonitor DataSource.

## Run it locally

Needs a JDK 21+; Groovy is optional (it runs the collection-script tests).

```bash
cd outage-reporter
./run-tests.sh                 # builds the jar, runs 72 JDK tests + 9 script tests, offline
OUTAGE_ADMIN_TOKEN=dev OUTAGE_DATA_DIR=/tmp/outage java -jar build/outage-reporter.jar
# open http://localhost:8080/  ·  health: curl -s localhost:8080/health
```

Break it on purpose and watch what LogicMonitor would see:

```bash
curl -s -XPOST -H 'X-Admin-Token: dev' 'localhost:8081/admin/chaos?mode=storm'   # or latency|errors|store
groovy scripts/collection.groovy 127.0.0.1:8080
curl -s -XPOST -H 'X-Admin-Token: dev' 'localhost:8081/admin/chaos?mode=off'
```

To deploy to AWS, monitor it in LogicMonitor and operate it, follow the
[guide](outage-reporter/docs/DOCUMENTATION.md): `deploy/aws-up.sh` builds the
VM from code in about five minutes.

## Where to look

| | |
|---|---|
| [outage-reporter/README.md](outage-reporter/README.md) | Design record: the domain, the health model, why each datapoint and threshold is what it is, fault injection, tests, known limitations |
| [outage-reporter/docs/DOCUMENTATION.md](outage-reporter/docs/DOCUMENTATION.md) | Architecture and object model diagrams, the API, AWS deployment, each LogicMonitor layer step by step, alerting, operations, troubleshooting |
| [outage-reporter/scripts/collection.groovy](outage-reporter/scripts/collection.groovy) | The LogicMonitor collection script |
| [outage-reporter/module/](outage-reporter/module/) | DataSource definitions: datapoints and thresholds |
| [Collateral/CTA_Presentation_Dennis_Katumalla.pdf](Collateral/CTA_Presentation_Dennis_Katumalla.pdf) | The presentation this environment accompanies |
