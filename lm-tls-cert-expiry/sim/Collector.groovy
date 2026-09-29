/**
 * Simulates one collector cycle for a DataSource against a set of resources:
 * AppliesTo -> Active Discovery -> collection per instance -> thresholds ->
 * alert messages. Returns plain maps so results can be printed or asserted on.
 *
 * Not simulated: complex datapoints, alert trigger/clear intervals across
 * polls, alert rules and escalation chains, and any collector helper classes
 * beyond hostProps/instanceProps.
 */
class Collector {
    final Map module
    final File moduleDir

    Collector(Map module, File moduleDir) {
        this.module = module
        this.moduleDir = moduleDir
    }

    /** Runs the full cycle for one resource. */
    Map runResource(Map resource) {
        // get(), not .properties: on a Map, Groovy 6 resolves .properties to the
        // object's bean properties rather than the "properties" key.
        Map<String, String> hostProps = Props.of((resource.get("properties") ?: [:]) as Map)
        hostProps.putIfAbsent("system.hostname", resource.hostname as String)
        hostProps.putIfAbsent("system.displayname", (resource.displayName ?: resource.hostname) as String)

        Map result = [resource: hostProps.get("system.displayname"), hostname: hostProps.get("system.hostname")]
        boolean applies = AppliesTo.matches(module.appliesTo as String, hostProps)
        result.appliesTo = [expression: module.appliesTo, matched: applies]
        if (!applies) return result

        int timeout = (module.collection?.timeoutSeconds ?: 60) as int
        String adSource = ScriptRunner.substitute(script(module.activeDiscovery.script as String), hostTokens(hostProps))
        ScriptResult ad = ScriptRunner.run(adSource, [hostProps: hostProps] as Map<String, Object>, timeout)
        DiscoveryParse parsed = Parsers.parseDiscovery(ad.stdout)
        result.discovery = [exitCode: ad.exitCode, timedOut: ad.timedOut, millis: ad.millis, stdout: ad.stdout,
                            stderr: ad.stderr, errors: parsed.errors, warnings: parsed.warnings,
                            instances: parsed.instances.collect { [wildvalue: it.wildvalue, wildalias: it.wildalias,
                                                                   description: it.description, props: it.props,
                                                                   invalidReason: it.invalidReason] }]

        // Documented collector behaviour: a non-zero exit keeps the instances
        // from the last good discovery; exit 0 replaces them with this output,
        // so exit 0 with no instances removes them all.
        if (ad.exitCode != 0) {
            result.discovery.outcome = "failed: previously discovered instances are kept unchanged"
            result.instances = []
            return result
        }
        result.discovery.outcome = parsed.instances ? "${parsed.instances.size()} instance(s)".toString()
                : "no instances: any previously discovered instances are removed"
        result.instances = parsed.instances.collect { collect(hostProps, it, timeout) }
        return result
    }

    private Map collect(Map<String, String> hostProps, DiscoveredInstance inst, int timeout) {
        Map r = [wildvalue: inst.wildvalue, name: inst.wildalias]
        Map<String, String> kv = [:]
        if (inst.invalidReason) {
            r.noData = inst.invalidReason
        } else {
            Map<String, String> instanceProps = Props.of(inst.props + [wildvalue: inst.wildvalue,
                                                                        wildalias: inst.wildalias,
                                                                        description: inst.description])
            Map<String, String> tokens = hostTokens(hostProps) + instanceProps +
                    [WILDVALUE: inst.wildvalue, WILDALIAS: inst.wildalias]
            String source = ScriptRunner.substitute(script(module.collection.script as String), tokens)
            ScriptResult run = ScriptRunner.run(source,
                    [hostProps: hostProps, instanceProps: instanceProps] as Map<String, Object>, timeout)
            r.exitCode = run.exitCode
            r.timedOut = run.timedOut
            r.millis = run.millis
            r.stdout = run.stdout
            r.stderr = run.stderr
            // Assumption: a failed script (non-zero exit or timeout) yields no
            // data for output-based datapoints.
            if (run.exitCode != 0) r.noData = run.timedOut ? "script timed out" : "script exited ${run.exitCode}".toString()
            else kv = Parsers.parseKeyValue(run.stdout)
        }

        r.datapoints = []
        r.alerts = []
        module.datapoints.each { Map dp ->
            double v = r.noData ? Double.NaN : Parsers.valueFor(kv, dp.key as String)
            r.datapoints << [name: dp.name, value: v]
            Threshold t = Threshold.from(dp.threshold as Map)
            String level = Alerts.level(t, v)
            if (level) r.alerts << alert(hostProps, inst, dp, t, level, v)
        }
        return r
    }

    private Map alert(Map<String, String> hostProps, DiscoveredInstance inst, Map dp, Threshold t, String level, double v) {
        Map<String, String> values = [
                HOST          : hostProps.get("system.displayname"),
                HOSTNAME      : hostProps.get("system.hostname"),
                INSTANCE      : inst.wildalias,
                DSIDESCRIPTION: inst.description,
                DATASOURCE    : module.displayName as String,
                DATAPOINT     : dp.name as String,
                VALUE         : Alerts.formatValue(v),
                THRESHOLD     : "${t.op} ${Alerts.formatValue(t."${level}" as double)}".toString(),
                LEVEL         : level,
        ]
        Map msg = (dp.alertMessage ?: module.alertMessage) as Map
        List subject = Alerts.render(msg.subject as String, values)
        List body = Alerts.render(msg.body as String, values)
        return [level: level, datapoint: dp.name, value: v, threshold: values.THRESHOLD,
                subject: subject[0], body: body[0], unknownTokens: ((subject[1] + body[1]) as Set) as List]
    }

    private static Map<String, String> hostTokens(Map<String, String> hostProps) {
        return hostProps + [HOSTNAME: hostProps.get("system.hostname")]
    }

    private String script(String path) {
        File f = new File(path)
        return (f.absolute ? f : new File(moduleDir, path)).getText("UTF-8")
    }
}
