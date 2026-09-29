import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import java.security.KeyStore

/**
 * A loopback TLS server presenting a self-signed certificate for localhost,
 * so end-to-end tests run offline. It completes each handshake and hangs up.
 */
class TlsServer implements Closeable {
    private static final char[] PASSWORD = "changeit".toCharArray()
    private final SSLServerSocket server

    /**
     * Generates a keystore with keytool. startDate uses keytool's relative
     * form, e.g. "-400d", to backdate the certificate.
     */
    static File keystore(File dir, String alias, int validityDays, String startDate = null) {
        File ks = new File(dir, "${alias}.p12")
        List<String> cmd = ["${System.getProperty('java.home')}/bin/keytool".toString(), "-genkeypair",
                            "-keystore", ks.path, "-storetype", "PKCS12", "-storepass", "changeit",
                            "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
                            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
                            "-validity", validityDays as String]
        if (startDate) cmd += ["-startdate", startDate]
        Process p = cmd.execute()
        StringBuilder out = new StringBuilder()
        p.waitForProcessOutput(out, out)
        if (p.exitValue() != 0) throw new IllegalStateException("keytool failed: ${out}")
        return ks
    }

    TlsServer(File keystore) {
        KeyStore ks = KeyStore.getInstance("PKCS12")
        keystore.withInputStream { ks.load(it, PASSWORD) }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.defaultAlgorithm)
        kmf.init(ks, PASSWORD)
        SSLContext ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        server = ctx.serverSocketFactory.createServerSocket(0, 50, InetAddress.loopbackAddress) as SSLServerSocket
        Thread.startDaemon("tls-server-${port}") {
            while (!server.closed) {
                try {
                    SSLSocket s = server.accept() as SSLSocket
                    try { s.startHandshake() } catch (IOException ignored) { } finally { s.close() }
                } catch (IOException ignored) {
                    // accept() fails once the server is closed; the loop then ends.
                }
            }
        }
    }

    int getPort() { server.localPort }

    /** A loopback port with nothing listening on it. */
    static int closedPort() {
        ServerSocket s = new ServerSocket(0, 1, InetAddress.loopbackAddress)
        int port = s.localPort
        s.close()
        return port
    }

    @Override
    void close() { server.close() }
}
