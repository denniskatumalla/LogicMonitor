import org.junit.runner.JUnitCore
import org.junit.runner.Result
import org.junit.runner.notification.Failure

/** Runs every test class and prints a summary. */
class AllTests {
    static boolean run() {
        Result result = JUnitCore.runClasses(AppliesToTest, ParsersTest, AlertsTest, ScriptRunnerTest, EndToEndTest)
        result.failures.each { Failure f ->
            println "FAIL ${f.testHeader}"
            println f.trace.readLines().take(12).collect { "    ${it}" }.join("\n")
        }
        println "${result.runCount} tests, ${result.failureCount} failures, ${result.runTime}ms"
        return result.wasSuccessful()
    }
}
