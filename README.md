# LogicMonitor

LogicMonitor modules, each in its own folder with its scripts, tests and documentation.

| Module | Type | What it does |
|---|---|---|
| [outage-reporter](outage-reporter/) | Application + DataSource | Outage-reporting service for a fictional electric utility (Example Power & Light), with a customer web UI, a ticket dispatcher and business KPIs, in plain Java 21 on one EC2 instance with a LogicMonitor collector. It covers the VM, OS, JVM, app and business layers, an `Outage_Reporter_Health` DataSource, a multi-step customer-journey web check, and latency, errors, store and storm break/fix. See its [guide](outage-reporter/docs/DOCUMENTATION.md). |
