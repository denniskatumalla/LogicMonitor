/*
 * TLS_Certificate_Expiry-  |  collection script
 *
 * Reads the leaf certificate from a TLS endpoint and reports its remaining
 * lifetime. Deliberately does NOT validate the chain: an expired or
 * self-signed certificate must still be readable, otherwise the module goes
 * blind at exactly the moment it matters. Trust is a separate concern,
 * reported by chainTrusted from a second, fully validating handshake.
 */
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

// On the collector, discovery's auto.tls.* instance properties say where to
// connect. Locally there are no instanceProps, so take host[:port] from an
// argument. binding.hasVariable, because a bare reference to an unbound
// variable throws.
String host
Integer port
if (binding.hasVariable("instanceProps") && instanceProps.get("auto.tls.host")) {
    host = instanceProps.get("auto.tls.host")
    port = (instanceProps.get("auto.tls.port") ?: "443") as Integer
} else {
    def cli = binding.hasVariable("args") ? args : null
    def bits = ((cli && cli.length > 0) ? cli[0] : "www.logicmonitor.com:443").tokenize(":")
    host = bits[0]
    port = (bits.size() > 1 ? bits[1] : "443") as Integer
}

// Read-only trust manager: accept anything so we can inspect the certificate.
def trustAll = [ new X509TrustManager() {
    void checkClientTrusted(X509Certificate[] chain, String authType) { }
    void checkServerTrusted(X509Certificate[] chain, String authType) { }
    X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0] }
} ] as TrustManager[]

// Per-handshake connect and read timeouts. Worst case is two handshakes x
// (5 s + 5 s) = 20 s plus DNS, well inside the collector's 60 s script limit.
int timeoutMs = 5000

// Connects and completes a handshake; caller closes the returned socket.
// verifyHost adds the same hostname check a browser does.
def handshake = { SSLSocketFactory factory, boolean verifyHost ->
    SSLSocket s = factory.createSocket() as SSLSocket
    try {
        s.connect(new InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs

        // Send SNI, or a multi-tenant endpoint hands back the wrong certificate.
        SSLParameters params = s.getSSLParameters()
        params.setServerNames([new SNIHostName(host)])
        if (verifyHost) params.setEndpointIdentificationAlgorithm("HTTPS")
        s.setSSLParameters(params)

        s.startHandshake()
        return s
    } catch (Exception e) {
        try { s.close() } catch (Exception ignored) { }
        throw e
    }
}

SSLSocket sock = null
try {
    def ctx = SSLContext.getInstance("TLS")
    ctx.init(null, trustAll, new java.security.SecureRandom())

    sock = handshake(ctx.socketFactory, false)

    def chain = sock.session.peerCertificates
    X509Certificate leaf = (X509Certificate) chain[0]

    long now  = System.currentTimeMillis()
    long days = (leaf.notAfter.time - now).intdiv(86400000L)
    long age  = (now - leaf.notBefore.time).intdiv(86400000L)

    // Second handshake through the JVM's default trust store. Any failure here
    // counts as untrusted; reachability is already reported by handshakeOk.
    int trusted = 0
    try {
        handshake(SSLContext.getDefault().socketFactory, true).close()
        trusted = 1
    } catch (Exception e) {
        System.err.println("chainTrusted=0: ${e}")
    }

    println "handshakeOk=1"
    println "daysUntilExpiry=${days}"
    println "daysSinceIssued=${age}"
    println "chainLength=${chain.size()}"
    println "chainTrusted=${trusted}"
    return 0

} catch (Exception e) {
    // Return 0, not 1. A non-zero exit means "no data" -> NaN -> nothing to
    // alert on, so a hard failure would render as monitoring silence.
    // Only handshakeOk is emitted: the other keys go NaN, so one outage raises
    // one alert rather than a second, misleading "expires in -1 days".
    println "handshakeOk=0"
    e.printStackTrace()
    return 0

} finally {
    try { sock?.close() } catch (Exception ignored) { }
}
