package com.fxc.pub.xmpp;

import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;
import org.jivesoftware.smack.ConnectionConfiguration.SecurityMode;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jxmpp.jid.impl.JidCreate;

/**
 * Builds Smack connections to stock Tigase for FxcPub's XMPP-client services (docs/DESIGN.md §4.3).
 * FxcPub interacts with Tigase strictly as a standard XMPP client — this factory is the single
 * place that knows how to reach the server.
 *
 * <p><b>Dev TLS:</b> for local development this uses {@code SecurityMode.disabled} — Tigase offers
 * STARTTLS but does not require it, and its auto-generated self-signed certificate otherwise fails
 * client-side PKIX validation. Production should install a CA-signed cert in Tigase and switch to
 * {@code SecurityMode.required} (see {@code .reference/tigase-xmpp/smack-client.md}).
 */
public final class XmppConnectionFactory {

    private final String host;
    private final int port;
    private final String domain;

    public XmppConnectionFactory(String host, int port, String domain) {
        this.host = host;
        this.port = port;
        this.domain = domain;
    }

    /** Connect and authenticate an existing account. */
    public XMPPTCPConnection connect(String username, String password) throws Exception {
        XMPPTCPConnection connection = new XMPPTCPConnection(baseConfig()
                .setUsernameAndPassword(username, password)
                .build());
        connection.connect();
        connection.login();
        return connection;
    }

    private XMPPTCPConnectionConfiguration.Builder baseConfig() throws org.jxmpp.stringprep.XmppStringprepException {
        return XMPPTCPConnectionConfiguration.builder()
                .setXmppDomain(JidCreate.domainBareFrom(domain))
                .setHost(host)
                .setPort(port)
                // Tigase requires TLS; trust its dev self-signed cert via Smack's own trust manager
                // hook (Smack validates through this, not through a custom SSLContext). Dev only.
                .setSecurityMode(SecurityMode.required)
                .setCustomX509TrustManager(TRUST_ALL)
                .setHostnameVerifier((h, s) -> true);
    }

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };
}
