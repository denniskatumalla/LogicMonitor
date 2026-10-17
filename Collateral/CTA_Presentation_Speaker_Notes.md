# CTA Presentation: speaker notes

## 1. Dennis Katumalla

About 45 seconds.

"I'm a hands-on AIOps and Observability practitioner. I started as a software engineer at Cap Gemini and Computer Associates, building expert systems, which was AI before it was mainstream. After a few years consulting on data management, I spent 16 years at CA, then Broadcom, as the lead technical architect on Fortune 500 accounts: about four years presales, then twelve focused on adoption and value realization.

The three numbers are what I'm proudest of. $40M+ is account-team ELA revenue where I was the lead architect, not a personal quota. For the last year I've been building agentic AIOps hands-on, which is why Edwin AI and Autonomous IT drew me to LogicMonitor.

Today: one customer story, the architecture behind it, the hands-on work I did on your platform, why I fit this role, and my first 90 days. Then I'll show you the environment I built and monitored with LogicMonitor."

## 2. Replace the incumbent APM platform at a Fortune 500 utility, without risking storm season

About 2 minutes for this slide and the next together.

"The engagement I want to walk you through ran for over two years at a Fortune 500 electric utility with millions of customers across several states. They were running Dynatrace for applications and SolarWinds for servers and databases. They were also a big user of our network monitoring, DX NetOps, especially for SD-WAN, which gave me a strong foothold at the customer. The business wanted to consolidate tools and lower cost, get broader coverage across network, infrastructure and legacy systems, and above all keep outage reporting reliable during hurricane season, when customers depend on it most.

I was the technical lead from evaluation through implementation. I worked hand in hand with the customer's outsourced monitoring team, six consultants, two in the U.S. and four in India, who owned every monitoring tool, including the Dynatrace we were replacing. I enabled them on the new platform, and every Friday I ran an enablement session open to anyone in the company, so adoption didn't depend on one team. I worked across the TIBCO BusinessWorks and EMS team, the Java team, the system admins and the DBAs to instrument their stacks, and I presented the solution architecture to the customer's Architecture Review Board for approval, more than once.

Three kinds of risk. Technical: 5,000+ agents to migrate across application, infrastructure and network. Political: I was leading the displacement of a tool that teams had built their workflows around. The pushback came from the TIBCO team. BusinessWorks is thread-intensive, and they were used to seeing every JVM thread and its state, running, blocked or waiting, in Dynatrace. DX didn't have that view. Timing: change freezes during hurricane season limit when you can cut anything over."

## 3. Phase it, run in parallel, prove it first

About 2 minutes.

"It started with the PoC. A typical PoC runs a week; this one ran two months, because every team was judging the new platform against a tool it already knew: TIBCO and Java against Dynatrace, the system admins and DBAs against SolarWinds. So I ran it as a separate PoC for each team. As each team joined, I added its objectives and success criteria, and the stakeholders and expectations grew with them. It cost time up front, but every team was satisfied before we migrated anything, so nobody was surprised later.

I ruled out a big-bang swap. I planned the migration in waves, ran both tools in parallel so nobody lost visibility, and I only signed off a wave once it reached parity on the alerts and dashboards that mattered. Nothing was left behind: everything migrated over the two years, with Dynatrace and SolarWinds running in parallel until the last wave.

The hardest gap was that thread view for TIBCO. I built an interim: I automated thread dumps with kill -3 and jcmd Thread.print and turned them into the same running, blocked and waiting picture. That bought time until our product team shipped the feature, within two releases, and I got the TIBCO team, the ones who'd pushed back, to work with us as a design partner and beta-test it.

One wave did break. In the lower environment the Java agent looked great, but the monitoring team had set its profile to High for maximum visibility, where best practice is Medium. High instruments things like socket I/O that you rarely need, and under production load the bytecode instrumentation overhead hurt application performance. The JVM team removed the agent straight away, which was the right call. I ran benchmark tests and recommended restarting on the Low profile, with less visibility, to rebuild trust; once the team was comfortable, I moved them to Medium, which gives the best balance of visibility and overhead.

The whole time I was also the technical owner for 14 other accounts. I could do that because I shared the implementation: I stayed the sole lead technical engineer, but the customer's monitoring team and its platform teams each carried the rollout for their own stack. Inside Broadcom we were a team of eight consultants, and we covered for each other: others picked up some of my tasks when I was stretched, and I did the same for them.

The result: I retired three tools, Dynatrace, SolarWinds and Hyperping for synthetics, cutting their observability tooling cost by 40%. I'll be honest, cost was the most appealing factor for them, and rolling it into an Enterprise License Agreement gave us a lot of leverage. But the bigger change was how they used it. They had used those tools purely for monitoring; I took them to higher-level use cases, more AIOps than they had before, service observability, SLIs and SLOs. That's what won the business stakeholders and the executives, and it drove both the sale and the adoption."

Then: "Happy to go deeper on any part of that."

## 4. One platform for every layer

About 2 minutes. Left to right: what I scoped, how it was collected, where it was correlated, who acted on it.

"I designed this target architecture. I prioritised the customer-facing apps, because outage reporting is what customers see during a storm: the outage map and reporting, the customer portal, billing and payments, and the mobile app. Behind them sit the internal systems those apps depend on: the customer information system, outage management, GIS, meter data from the smart meters, and work and asset management, with TIBCO integrating many of them. A slow billing page is often a slow CIS call, so I monitored both sides. I put DX APM on those apps, UIM across the data centers and NetOps on fault isolation across the network, and I extended coverage to Kubernetes and AWS. I fed everything into DX Operational Observability for topology, correlation and service views, then into the NOC, ITSM and executive dashboards."

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
