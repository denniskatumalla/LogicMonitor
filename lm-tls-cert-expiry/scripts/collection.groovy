/*
 * TLS_Certificate_Expiry-  |  collection script
 *
 * Reads the leaf certificate from a TLS endpoint and reports its remaining
 * lifetime. Deliberately does NOT validate the chain: an expired or
 * self-signed certificate must still be readable, otherwise the module goes
 * blind at exactly the moment it matters. Trust is a separate concern.
 */
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SNIHostName
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

// LogicMonitor substitutes this token before execution. If it arrives literal,
// we're running locally -- fall back to an argument. Same guard, two purposes.
// binding.hasVariable: the collector doesn't bind `args`, so a bare reference would throw.
def wildvalue = "##WILDVALUE##"
if (wildvalue.startsWith("##")) {
    def cli = binding.hasVariable("args") ? args : null
    wildvalue = (cli && cli.length > 0) ? cli[0] : "www.logicmonitor.com:443"
}

def bits = wildvalue.tokenize(":")
def host = bits[0]
def port = (bits.size() > 1 ? bits[1] : "443") as Integer

// Read-only trust manager: accept anything so we can inspect the certificate.
def trustAll = [ new X509TrustManager() {
    void checkClientTrusted(X509Certificate[] chain, String authType) { }
    void checkServerTrusted(X509Certificate[] chain, String authType) { }
    X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0] }
} ] as TrustManager[]

SSLSocket sock = null
try {
    def ctx = SSLContext.getInstance("TLS")
    ctx.init(null, trustAll, new java.security.SecureRandom())

    sock = ctx.socketFactory.createSocket() as SSLSocket
    sock.connect(new InetSocketAddress(host, port), 10000)
    sock.soTimeout = 10000

    // Send SNI, or a multi-tenant endpoint hands back the wrong certificate.
    SSLParameters params = sock.getSSLParameters()
    params.setServerNames([new SNIHostName(host)])
    sock.setSSLParameters(params)

    sock.startHandshake()

    def chain = sock.session.peerCertificates
    X509Certificate leaf = (X509Certificate) chain[0]

    long now  = System.currentTimeMillis()
    long days = (leaf.notAfter.time - now).intdiv(86400000L)
    long age  = (now - leaf.notBefore.time).intdiv(86400000L)

    println "handshakeOk=1"
    println "daysUntilExpiry=${days}"
    println "daysSinceIssued=${age}"
    println "chainLength=${chain.size()}"
    return 0

} catch (Exception e) {
    // Return 0, not 1. A non-zero exit means "no data" -> NaN -> nothing to
    // alert on, so a hard failure would render as monitoring silence.
    println "handshakeOk=0"
    println "daysUntilExpiry=-1"
    println "daysSinceIssued=-1"
    println "chainLength=0"
    e.printStackTrace()
    return 0

} finally {
    try { sock?.close() } catch (Exception ignored) { }
}
