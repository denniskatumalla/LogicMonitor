/*
 * lmsim -- runs a DataSource through a simulated collector cycle.
 *
 *   ./lmsim [--module FILE] [--resources FILE] [--resource NAME] [--json]
 *
 * Exit status: 0 if the module ran cleanly (alerts are expected output),
 * 1 if discovery produced errors or unrecognised alert tokens, 2 on bad usage.
 */
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

Map<String, String> opts = [module: "module/TLS_Certificate_Expiry.json", resources: "examples/resources.json"]
boolean json = false
List<String> argv = args as List<String>
for (int i = 0; i < argv.size(); i++) {
    String a = argv[i]
    if (a == "--json") { json = true; continue }
    if (a in ["--module", "--resources", "--resource"] && i + 1 < argv.size()) { opts[a.substring(2)] = argv[++i]; continue }
    System.err.println("usage: lmsim [--module FILE] [--resources FILE] [--resource NAME] [--json]")
    System.exit(2)
}

File moduleFile = new File(opts.module)
Map module = new JsonSlurper().parse(moduleFile) as Map
List<Map> resources = (new JsonSlurper().parse(new File(opts.resources)) as Map).resources as List<Map>
if (opts.resource) resources = resources.findAll { it.displayName == opts.resource || it.hostname == opts.resource }
if (!resources) {
    System.err.println("lmsim: no matching resources")
    System.exit(2)
}

Collector collector = new Collector(module, moduleFile.absoluteFile.parentFile)
List<Map> results = resources.collect { collector.runResource(it) }

boolean problems = results.any { r ->
    r.discovery?.errors || r.instances?.any { i -> i.alerts.any { it.unknownTokens } }
}

if (json) {
    // JSON has no NaN; emit null, which is what "no data" means anyway.
    def clean
    clean = { o ->
        if (o instanceof Map) return o.collectEntries { k, v -> [k, clean(v)] }
        if (o instanceof List) return o.collect { clean(it) }
        if (o instanceof Double && ((Double) o).isNaN()) return null
        return o
    }
    println JsonOutput.prettyPrint(JsonOutput.toJson([module: module.name, resources: clean(results)]))
    System.exit(problems ? 1 : 0)
}

String indent(String text, String pad) { text.readLines().collect { pad + it }.join("\n") }

println "DataSource ${module.name}  (${module.displayName})"
results.each { Map r ->
    println ""
    println "Resource ${r.resource}  [${r.hostname}]"
    println "  AppliesTo   ${r.appliesTo.expression}  ->  ${r.appliesTo.matched ? 'MATCH' : 'no match'}"
    if (!r.appliesTo.matched) return

    Map ad = r.discovery
    println "  Discovery   exit=${ad.exitCode}${ad.timedOut ? ' (timed out)' : ''}  ${ad.millis}ms  ${ad.outcome}"
    ad.errors.each { println "    ERROR   ${it}" }
    ad.warnings.each { println "    warning ${it}" }
    ad.instances.each { i ->
        String props = i.props.collect { k, v -> "${k}=${v}" }.join(" ")
        println "    ${i.wildvalue.padRight(34)} ${i.wildalias.padRight(34)} ${props}"
    }
    if (ad.stderr?.trim()) println "    stderr:\n${indent(ad.stderr.trim(), '      ')}"

    r.instances.each { Map inst ->
        println ""
        String timing = inst.containsKey("exitCode") ? "exit=${inst.exitCode}  ${inst.millis}ms" : ""
        println "  Instance ${inst.name}  ${timing}${inst.noData ? '  NO DATA: ' + inst.noData : ''}"
        inst.datapoints.each { dp -> println "    ${(dp.name as String).padRight(18)} ${Alerts.formatValue(dp.value as double)}" }
        String firstErr = inst.stderr?.trim()?.readLines()?.find()
        if (firstErr) println "    stderr: ${firstErr}"
        inst.alerts.each { a ->
            println "    ALERT ${(a.level as String).toUpperCase()}  ${a.datapoint}=${Alerts.formatValue(a.value as double)}  (${a.threshold})"
            println "      Subject: ${a.subject}"
            List<String> body = (a.body as String).readLines()
            println "      Body:    ${body[0]}"
            body.drop(1).each { println "               ${it}" }
            a.unknownTokens.each { println "      ERROR  unrecognised token ##${it}##" }
        }
    }
}

int alertCount = results.sum { (it.instances ?: []).sum { i -> i.alerts.size() } ?: 0 } as int
println ""
println "${results.size()} resource(s), ${alertCount} alert(s)${problems ? ', PROBLEMS FOUND' : ''}"
System.exit(problems ? 1 : 0)
