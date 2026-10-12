# CTA Presentation: speaker notes

## 1. Dennis Katumalla

About 45 seconds.

"I'm a hands-on AIOps and Observability practitioner. I started as a software engineer at Cap Gemini and Computer Associates, building expert systems, which was AI before it was mainstream. After a few years consulting on data management, I spent 16 years at CA, then Broadcom, as the lead technical architect on Fortune 500 accounts: about four years presales, then twelve focused on adoption and value realization.

The three numbers are what I'm proudest of. $40M+ is account-team ELA revenue where I was the lead architect, not a personal quota. For the last year I've been building agentic AIOps hands-on, which is why Edwin AI and Autonomous IT drew me to LogicMonitor.

Today: one customer story, the architecture behind it, the hands-on work I did on your platform, why I fit this role, and my first 90 days. Then I'll show you the environment I built and monitored with LogicMonitor."

## 2. Replace the incumbent APM platform at a Fortune 500 utility, without risking storm season

About 2 minutes for this slide and the next together.

"The engagement I want to walk you through ran for over two years at a Fortune 500 electric utility with millions of customers across several states. They were running Dynatrace. The business wanted to consolidate tools and lower cost, get broader coverage across network, infrastructure and legacy systems, and above all keep outage reporting reliable during hurricane season, when customers depend on it most.

I was the technical lead from evaluation through implementation. [Team and what I owned.]

Three kinds of risk. Technical: 5,000+ agents to migrate across application, infrastructure and network. Political: I was leading the displacement of a tool that teams had built their workflows around. [Who resisted, and how I won them over.] Timing: change freezes during hurricane season limit when you can cut anything over."

## 3. Phase it, run in parallel, prove it first

About 2 minutes.

"I ruled out a big-bang swap. I planned the migration in waves, ran both tools in parallel so nobody lost visibility, and I only signed off a wave once it reached parity on the alerts and dashboards that mattered. [The hardest technical issue, and how I fixed it.] [A moment something broke, and how I restored it.] The whole time I was also covering [other accounts]; I kept them going by [method].

The result was [tools retired, savings, MTTR or incident change], and the account [renewed / expanded]."

Then: "Happy to go deeper on any part of that."

## 4. One platform for every layer

About 2 minutes. Left to right: what I scoped, how it was collected, where it was correlated, who acted on it.

"I designed this target architecture. I prioritised the customer-facing apps, because outage reporting is what customers see during a storm. I put DX APM on those apps, UIM across the data centers and NetOps on fault isolation across the network, and I extended coverage to Kubernetes and AWS. I fed everything into DX Operational Observability for topology, correlation and service views, then into the NOC, ITSM and executive dashboards."

Bottom row: "In LogicMonitor terms, hubs and robots become Collector groups, probes become LogicModules driven by AppliesTo, NetOps fault isolation becomes topology and alert dependencies, and alarm correlation becomes Edwin AI. That's the mapping I'd use for a customer moving off UIM or DX."

Trade-offs, if asked: agent vs agentless in restricted zones, polling intervals, SNMPv3 vs v2c, data retention.

## 5. One environment, two audiences

About 90 seconds, then switch to the demo.

"I built a small version of the service from my customer story, a utility's outage-reporting site, from a blank AWS account, and I monitored it in LogicMonitor end to end. I deployed the VM and app from code, installed a Collector, connected AWS with a read-only role, and wrote a Groovy DataSource that turns the app's health into technical and business metrics.

The same environment serves two audiences, so I built two dashboards. An executive asks about outcomes and risk: can customers report an outage, how many are without power, and how long are they waiting for an estimate. An engineer asks what's broken, where, and who has the ticket: latency, errors, the JVM, the OS, the logs, and alerts routed to on-call with one alert per cause.

Let me show you, starting with the executive view."

## 6. I've done every part of this role

About 60–75 seconds.

"Three reasons. Customer outcomes: post-sale adoption and expansion has been my job for twelve years, and my measures were technical wins, adoption and renewals. Observability depth: many of LogicMonitor's new customers are migrating off the tools I spent sixteen years with, so I know where the problems hide in those migrations. And agentic AI: for the past year I've been building multi-agent AIOps hands-on, with governance controls, which is the closed-loop direction of Edwin AI and Autonomous IT.

On the right is the role as posted. I've done each of these, and I'm happy to go deeper on any row."

Detail for any row: POCs (success criteria written up front), technical advisory (the migration mapping on slide 4), feedback to product (turning field input into Scout AI's product strategy), training (the global developer culture program I founded).

## 7. Learn, then contribute, then lead

About 90 seconds.

"First 30 days I'm learning: certifications, Edwin AI, shadowing your senior CTAs, and meeting every account I'm given so I have a health and KPI baseline. Days 31 to 60 I own the day-to-day technical relationships, agree a success plan with measurable KPIs on each account, land quick wins like critical services monitored and noise tuned, and run my first best-practices workshop. By 90 days I'm running value reviews with customer leadership, running POCs of new features with customers, starting with Edwin AI, feeding what I hear into the PDE roadmap, and I've shared something reusable with the team. A DX or UIM migration playbook is the obvious one for me."

Then ask: "What would you want to see from me at 90 days?"

Transition: "Let me show you what I built."
