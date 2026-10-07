# LogicMonitor

LogicMonitor modules, each in its own folder with its scripts, tests and documentation.

| Module | Type | What it does |
|---|---|---|
| [lm-tls-cert-expiry](lm-tls-cert-expiry/) | DataSource | Days until TLS certificate expiry, chain trust and reachability for any `host:port` listed in the `tls.endpoints` resource property. Includes an offline collector simulator and tests. See its [guide](lm-tls-cert-expiry/docs/DOCUMENTATION.md). |
| [shortlink](shortlink/) | Application + DataSource | URL-shortener REST service in plain Java 21, deployed to one EC2 instance with a LogicMonitor collector. Covers VM, OS, JVM, app, synthetic checks and logs, with a `Shortlink_Health` script DataSource, fault injection for live break/fix, cloud-init deployment and offline tests. See its [guide](shortlink/docs/DOCUMENTATION.md). |
| [outage-reporter](outage-reporter/) | Application + DataSource | Outage-reporting service for a fictional electric utility (Example Power & Light), with a customer web UI, a ticket dispatcher and business KPIs, in plain Java 21 on one EC2 instance with a LogicMonitor collector. Successor to shortlink: VM, OS, JVM, app and business layers, an `Outage_Reporter_Health` DataSource, a multi-step customer-journey web check, and latency, errors, store and storm break/fix. See its [guide](outage-reporter/docs/DOCUMENTATION.md). |
