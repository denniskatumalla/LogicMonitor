package shortlink;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static shortlink.Assert.eq;
import static shortlink.Assert.ok;

/**
 * Runs the packaged jar the way systemd does, with the JMX flags from the
 * docs, and reads it over remote JMX as the LogicMonitor collector would.
 */
class ProcessTest {

    static Path jar() {
        return Path.of(System.getProperty("shortlink.jar", "build/shortlink.jar"));
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @TestRunner.Test
    void jarServesRemoteJmxAndStopsCleanlyOnSigterm() throws Exception {
        ok("build the jar first: " + jar(), Files.exists(jar()));
        Path tmp = LinkStoreTest.tempDir();
        Path log = tmp.resolve("stdout.log");
        int port = freePort();
        int jmxPort = freePort();
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m",
                "-Dcom.sun.management.jmxremote.port=" + jmxPort,
                "-Dcom.sun.management.jmxremote.rmi.port=" + jmxPort,
                "-Dcom.sun.management.jmxremote.host=127.0.0.1",
                "-Djava.rmi.server.hostname=127.0.0.1",
                "-Dcom.sun.management.jmxremote.authenticate=false",
                "-Dcom.sun.management.jmxremote.ssl=false",
                "-jar", jar().toString()));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile());
        pb.environment().put("SHORTLINK_BIND", "127.0.0.1");
        pb.environment().put("SHORTLINK_PORT", String.valueOf(port));
        pb.environment().put("SHORTLINK_DATA_DIR", tmp.resolve("data").toString());
        Process p = pb.start();
        try {
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> health = null;
            for (int i = 0; i < 100 && health == null; i++) {
                try {
                    health = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).build(),
                            HttpResponse.BodyHandlers.ofString());
                } catch (IOException notYet) {
                    Thread.sleep(100);
                }
            }
            ok("jar came up: " + Files.readString(log), health != null && health.statusCode() == 200);
            http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build(),
                    HttpResponse.BodyHandlers.discarding());

            JMXServiceURL url = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://127.0.0.1:" + jmxPort + "/jmxrmi");
            try (JMXConnector c = JMXConnectorFactory.connect(url)) {
                MBeanServerConnection mbs = c.getMBeanServerConnection();
                eq(1L, mbs.getAttribute(new ObjectName("shortlink:type=Stats"), "RequestsTotal"));
                eq(0, mbs.getAttribute(new ObjectName("shortlink:type=Stats"), "StatusCode"));
                CompositeData heap = (CompositeData) mbs.getAttribute(new ObjectName("java.lang:type=Memory"), "HeapMemoryUsage");
                ok("JVM metrics are there too", (Long) heap.get("used") > 0);
            }

            p.destroy();
            ok("exits within 10 s of SIGTERM", p.waitFor(10, TimeUnit.SECONDS));
            eq("JVM exit code after SIGTERM (systemd needs SuccessExitStatus=143)", 143, p.exitValue());
            String out = Files.readString(log);
            ok("logged startup: " + out, out.contains("event=started"));
            ok("ran the shutdown hook: " + out, out.contains("event=stopped"));
            ok("admin disabled without a token", out.contains("event=admin_disabled"));
        } finally {
            p.destroyForcibly();
            LinkStoreTest.delete(tmp);
        }
    }
}
