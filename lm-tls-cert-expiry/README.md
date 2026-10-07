# TLS_Certificate_Expiry-

A LogicMonitor DataSource that reports how long the TLS certificate on any
`host:port` has left to live, whether it is trusted, and whether the endpoint
answered at all. Endpoints are listed in a single resource property; the
module discovers and monitors them with no manual assignment.

For architecture diagrams, quick-start commands, deployment and
troubleshooting, see **[docs/DOCUMENTATION.md](docs/DOCUMENTATION.md)**.

## Contract

```
Module:          TLS_Certificate_Expiry-
Applies to:      resources with property tls.endpoints set
Input property:  tls.endpoints = host[:port],host[:port],...   (port defaults to 443)
Instances:       one per endpoint, wildvalue = host_port, name = host:port
Datapoints:      handshakeOk (gauge), daysUntilExpiry (gauge),
                 daysSinceIssued (gauge), chainLength (gauge),
                 chainTrusted (gauge)
Interval:        3600s collection, 3600s discovery
```

## Why a DataSource, not a Website check

LogicMonitor Website checks can watch certificate expiry too, but they run
from LogicMonitor's public checkpoints and speak HTTP(S). This module runs on
your own collector, which means it can:

- reach **internal** endpoints that are never exposed to the internet;
- read certificates on **any implicit-TLS service**, e.g. LDAPS (636), SMTPS
  (465), IMAPS (993), or a TLS-fronted database or message broker, not only
  web servers;
- produce ordinary per-instance datapoints, so the usual graphs, dashboards,
  thresholds and alert rules apply.

Certificate lifetimes are getting shorter. Under the CA/Browser Forum schedule,
the maximum is 200 days now and drops to 47 days by 2029. Renewal is becoming
routine, and routine automation fails quietly, so expiry monitoring matters
more than it used to.

## Configuration

Set one property on the resource whose collector should run the checks.
The resource itself does not have to be one of the endpoints.

```
tls.endpoints = www.example.com:443,ldap.corp.local:636,mail.example.com:465
```

- Comma-separated `host[:port]`; whitespace around entries is ignored.
- Port defaults to `443`.
- Duplicates are collapsed (`a.com` and `a.com:443` are one instance).
- An empty value produces no instances and no error.
- Entries that aren't a valid `host[:port]` (IPv6 literals, spaces, bad
  ports) are skipped and named on discovery's stderr, rather than becoming
  instances that can never collect.
- Hostnames are sent as SNI, so shared-IP and CDN-fronted endpoints return the
  right certificate. Use the name clients use, not an IP, wherever possible.

Setting the property can be done on a resource group to apply it to every
member, but each member's collector then checks every endpoint. Normally one
resource per list is what you want.

## AppliesTo

```
tls.endpoints =~ ".+"
```

The module targets itself. Setting the property brings monitoring up, and
clearing it takes monitoring down. There's no manual assignment and no
separate list to keep in sync with the property.

## Instances

Active Discovery (Groovy, every hour) splits `tls.endpoints` into one instance
per endpoint:

| Field | Value |
|---|---|
| wildvalue (instance ID) | `host_port` |
| wildalias (name shown) | `host:port` |
| description | `TLS endpoint <host> on port <port>` |
| auto properties | `auto.tls.host`, `auto.tls.port` |

The ID can't be `host:port`: LogicMonitor documents `=`, `:`, `\`, `#` and
space as invalid in a wildvalue and returns NoData for such instances. The
display name has no such restriction, so it keeps the form people recognise.
Collection reads `auto.tls.host` and `auto.tls.port` through `instanceProps`
instead of parsing the ID back apart.

## Datapoints

All datapoints are **gauges**. Days remaining is a level, not a rate, so
counter and derive types would be wrong here.

| Datapoint | Meaning | Threshold |
|---|---|---|
| `handshakeOk` | `1` if a TLS handshake completed, `0` if the endpoint could not be reached or refused TLS. When it is `0`, the other four datapoints are `NaN` | `!= 1` → critical |
| `daysUntilExpiry` | Whole days until the leaf certificate's `notAfter`; negative once expired | `< 30` warn, `< 14` error, `< 7` critical |
| `daysSinceIssued` | Whole days since `notBefore` | none (context; a sudden drop means the cert was reissued) |
| `chainLength` | Certificates the server presented | none (a drop to 1 on a public site usually means a missing intermediate) |
| `chainTrusted` | `1` if a second handshake with full validation succeeds (default trust store and hostname check), else `0` | `!= 1` → error |

### Why 30 / 14 / 7

- **30 days (warning):** enough time for a normal change window, a purchase
  order, or a ticket in another team's queue.
- **14 days (error):** the normal process has had two weeks and hasn't
  finished, so escalate.
- **7 days (critical):** emergency renewal territory.

For ACME-managed certificates, clients usually
renew at around 30 days remaining, so a cert may briefly show 29 before renewal
runs. If that warning is noise in your estate, lower the warning to about 20
days. An ACME cert still below 20 days means automation has failed.

### Why the module reads certificates it doesn't trust

The first handshake accepts any certificate. That's deliberate. If it
validated the chain, an expired or self-signed certificate would fail the
handshake and the module would report nothing, at exactly the moment the alert
matters. Trust is measured separately by `chainTrusted`, so expiry and trust
are reported independently.

### Why failures exit 0

If the script exits non-zero, the collection counts as failed and its output
datapoints record no data. They go to `NaN`, and `NaN` doesn't trigger
thresholds. So a dead endpoint would show up
as missing data rather than an alert. Instead, the script always exits 0 and
reports a failure as `handshakeOk=0`, which is alertable. Exception details go
to stderr for the collector logs.

On failure, the script prints only `handshakeOk=0`. The other datapoints are
left out on purpose, so they record `NaN` for that poll. The alternative was
sentinel values such as `daysUntilExpiry=-1`, but that would cross the
critical expiry threshold. One outage would then raise two critical alerts,
and the second would wrongly say the certificate expires in -1 days. With this
approach, each failure raises exactly one alert, and it names the actual
problem. On graphs, an outage shows up as a gap in the expiry line.

LogicMonitor can also turn a script's exit code into a datapoint. That would
work too, but `handshakeOk` keeps the whole contract in stdout, where the Raw
Data tab and the simulator both show it.

## Collector requirements

- Any collector OS. The scripts use only the JDK's standard TLS classes.
- Any collector Groovy runtime. The scripts avoid Groovy 3+ syntax such as
  `!in`. The test suite passes on Groovy 2.4 (JDK 8) and Groovy 6 (JDK 25),
  so they run on both Groovy 2 and Groovy 4 collectors.
- DNS resolution and outbound TCP from the collector to **every endpoint on its
  port**. Proxies are not used; the socket connects directly.
- `chainTrusted` validates against the **collector's JRE trust store**.
  Endpoints signed by a private CA report `0` until that CA is imported there.

Cost: two TLS handshakes per endpoint per hour. Each handshake has a 5 s
connect timeout and a 5 s read timeout. The worst case is 20 s plus DNS,
comfortably inside the collector's 60 s script limit. An unreachable endpoint
fails the first handshake, so the second is skipped.

## Known limitations

- **Leaf certificate only.** Expiry is read from the certificate the server
  presents first. An intermediate that expires before the leaf is not
  reported directly. It shows up only as `chainTrusted=0` once it has
  expired.
- **No revocation checking.** OCSP and CRL are not consulted. A revoked
  certificate can report `chainTrusted=1`.
- **Implicit TLS only.** Protocols that upgrade with STARTTLS (SMTP 25/587,
  LDAP 389, IMAP 143, PostgreSQL) are not supported. The handshake fails and
  reports `handshakeOk=0`.
- **No expiry data while unreachable.** When the handshake fails, no expiry is
  recorded for that poll. A certificate that crosses a threshold during an
  outage alerts on the first successful poll afterwards.
- **No client-certificate (mutual TLS) authentication.** Endpoints that
  require a client cert may fail the handshake.
- **No proxy support.** The collector needs a direct route to each endpoint.
- **Day resolution, truncated.** A certificate that expired less than 24 hours
  ago reports `0`, not `-1`. The critical threshold catches it anyway.
- **Hourly polling.** A certificate that is swapped out is noticed within one
  collection interval, not immediately.
- **IPv4/IPv6.** Whichever address the JVM resolves first is the one checked.
  IPv6 literals can't be listed in `tls.endpoints`; use a hostname.

## Repository layout

```
docs/DOCUMENTATION.md                Architecture, quick start, deployment
                                     and operations guide
module/TLS_Certificate_Expiry.json   The DataSource definition: AppliesTo,
                                     intervals, datapoints, thresholds, messages
scripts/active_discovery.groovy      Active Discovery script
scripts/collection.groovy            Collection script
sim/                                 Collector simulator (lmsim)
tests/                               JUnit tests, offline
examples/resources.json              Sample resources for ./lmsim
lmsim, run-tests.sh                  Entry points
```

<!-- TODO after portal build: add the exported module JSON to the layout above. -->

## Running the scripts locally

On the collector, the scripts read their input from `hostProps` and
`instanceProps`. Run directly, those aren't bound, and each script falls back
to a command-line argument. So the file you run locally is the same file that
goes into the module.

```bash
groovy scripts/collection.groovy expired.badssl.com:443
groovy scripts/active_discovery.groovy "www.example.com, ldap.corp.local:636"
```

Useful test endpoints: `expired.badssl.com`, `self-signed.badssl.com`,
`untrusted-root.badssl.com`, `wrong.host.badssl.com`, and a closed local
port such as `127.0.0.1:9999`.

## Collector simulator

This module was built without access to a LogicMonitor portal. To test it as
a module, not just as two scripts, `sim/` reproduces the parts of the
collector's execution model that the module depends on:

| Stage | What lmsim does |
|---|---|
| AppliesTo | Evaluates the expression against each resource's properties: `\|\|`, `&&`, `!`, `()`, `==`, `!=`, `=~`, `!~`, `exists()`, `hasCategory()`. Names and `=~` are case-insensitive. |
| Script execution | Replaces `##TOKEN##`s, binds `hostProps` and `instanceProps` (and not `args`), captures stdout and stderr, uses the return value as the exit code, and abandons the script after the timeout (60 s). |
| Active Discovery | Parses `id##name##description####auto.k=v&...`. Rejects malformed lines and duplicate IDs. Marks wildvalues with `= : \ #` or space as NoData. Exit ≠ 0 keeps the previous instances; exit 0 with no output removes them. |
| Collection | Runs once per instance and reads `key=value` lines into datapoints. A missing or non-numeric key is `NaN`. A failed or timed-out script is no data. |
| Alerting | Applies each datapoint's static threshold, highest severity first. `NaN` never alerts. Fills alert-message tokens and flags any it doesn't recognise. |

```bash
./lmsim                            # module + examples/resources.json, live endpoints
./lmsim --resource collector01 --json
./run-tests.sh                     # 20 tests, offline, about 5 s
```

`./lmsim` exits 1 if discovery reports errors or an alert message uses an
unknown token, so it can gate a commit or a CI job.

The tests start local TLS servers with `keytool`-generated certificates, one
valid and one that expired about a year ago, so they need no network. They
cover:
- the full cycle, including one alert per outage;
- AppliesTo on missing and empty properties;
- invalid endpoints;
- failed and timed-out collection;
- discovery's keep-or-remove behaviour.

One test runs the module's original `host:port` IDs through the simulator and
shows every instance collecting NoData. That's the bug the simulator caught.

**Where it follows the documentation, and where it assumes.** The documented
parts are:
- the discovery output format and wildvalue restrictions;
- `hostProps` and `instanceProps`;
- case-insensitive AppliesTo;
- the 1-minute script limit;
- discovery's exit-code behaviour;
- `op warning error critical` thresholds.

Assumptions, each noted in the code:
- a missing property reads as `""`;
- `=~` matches anywhere in the value;
- unknown `##TOKENS##` are left as written;
- a failed collection script means no data for its datapoints.

Not simulated: complex datapoints, alert trigger and clear intervals, alert
rules and escalation chains, and the collector's helper classes (SNMP, HTTP,
and so on).

## Building it in a portal

`module/TLS_Certificate_Expiry.json` is this repository's own description of
the DataSource, not LogicMonitor's export format. Enter each field into
**Modules → Add → DataSource** (multi-instance, Embedded Groovy for both
discovery and collection). Paste the two scripts unchanged. Add each
datapoint as a Key-Value Pairs datapoint with the key and thresholds given.

Then:
1. Set `tls.endpoints` on a resource.
2. Run Active Discovery. You should see one instance per endpoint, named
   `host:port`.
3. Compare an instance's Raw Data tab with `./lmsim` output for the same
   endpoint.

<!-- TODO after portal build: export the module to JSON, commit it, and
     document the import path here. -->
