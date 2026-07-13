package com.fxc.pub;

import com.fxc.common.config.FxcConfig;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import quickfix.SessionSettings;

/**
 * FxcPub: XMPP-native publication component (docs/DESIGN.md §4.3). Stock, unmodified Tigase runs as
 * a separate service; this process is the FXC application layer — a trusted XMPP client plus an
 * embedded GridGain node and a FIX drop-copy acceptor. Blocks until interrupted.
 *
 * <p>Requires a running Tigase (see {@code docker/tigase/}) and the provisioned {@code pub-service}
 * account. The Mastodon REST gateway is a separate, later addon (Phase 7).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        FxcConfig config = loadConfig();

        String gridInstance = config.getString("gridgain.instanceName", "fxc-pub");
        int gridDiscoveryPort = config.getInt("gridgain.discoveryPort", 47520);
        String workDir = config.getString("gridgain.workDir",
                Path.of(System.getProperty("java.io.tmpdir"), "fxc-pub-ignite").toString());

        String xmppHost = config.getString("xmpp.host", "localhost");
        int xmppPort = config.getInt("xmpp.port", 5222);
        String xmppDomain = config.getString("xmpp.domain", "fxc.local");
        String xmppUser = config.getString("xmpp.user", "pub-service");
        String xmppPassword = config.getString("xmpp.password", "secret");

        int fixPort = config.getInt("fix.acceptor.port", 9878);

        System.out.println("FxcPub starting (grid='" + gridInstance + "', XMPP " + xmppUser + "@"
                + xmppDomain + " via " + xmppHost + ":" + xmppPort + ", FIX drop-copy :" + fixPort + ")...");

        PubServer server = PubServer.start(gridInstance, gridDiscoveryPort, workDir,
                xmppHost, xmppPort, xmppDomain, xmppUser, xmppPassword,
                dropCopyAcceptorSettings(fixPort));

        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("FxcPub stopping...");
            server.close();
            shutdown.countDown();
        }));
        System.out.println("FxcPub started. Ctrl-C to stop.");
        shutdown.await();
    }

    private static SessionSettings dropCopyAcceptorSettings(int port) throws Exception {
        String cfg = """
                [DEFAULT]
                ConnectionType=acceptor
                BeginString=FIX.4.4
                SenderCompID=FXCPUB
                UseDataDictionary=Y
                DataDictionary=FIX44.xml
                StartTime=00:00:00
                EndTime=00:00:00
                HeartBtInt=30
                SocketAcceptPort=%d

                [SESSION]
                TargetCompID=BROKER1

                [SESSION]
                TargetCompID=BROKER2
                """.formatted(port);
        return new SessionSettings(new ByteArrayInputStream(cfg.getBytes(StandardCharsets.UTF_8)));
    }

    private static FxcConfig loadConfig() {
        Path confFile = Path.of("conf", "fxcpub.conf");
        return Files.exists(confFile) ? FxcConfig.load(confFile) : FxcConfig.empty();
    }
}
