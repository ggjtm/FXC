package com.fxc.broker;

import com.fxc.broker.oms.FixSettingsFactory;
import com.fxc.common.config.FxcConfig;
import com.fxc.common.store.ColdStore;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * FxcBroker: a minimal OFX brokerage with an OMS. Connects to FxcExchange via FIX and accepts OFX
 * from FxcInvestor instances.
 *
 * <p>Boots an embedded GridGain node, seeds a dev account, connects a FIX initiator to the
 * exchange, and starts the OFX HTTP server. Blocks until interrupted.
 *
 * <p>Publication to FxcPub (FIX drop-copy + XMPP bot) is deferred while FxcPub/Tigase is on hold
 * (see FxcBroker/docs/PROBLEMS.md B4).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        FxcConfig config = loadConfig();

        String gridInstance = config.getString("gridgain.instanceName", "fxc-broker");
        int gridDiscoveryPort = config.getInt("gridgain.discoveryPort", 47510);
        String workDir = config.getString("gridgain.workDir",
                Path.of(System.getProperty("java.io.tmpdir"), "fxc-broker-ignite").toString());

        String exchangeHost = config.getString("fix.exchange.host", "localhost");
        int exchangePort = config.getInt("fix.exchange.port", 9876);
        String senderCompId = config.getString("fix.senderCompId", "BROKER1");
        String pubHost = config.getString("fix.pub.host", "localhost");
        int pubPort = config.getInt("fix.pub.port", 9878);
        boolean dropCopyEnabled = config.getBoolean("fix.pub.enabled", true);

        String ofxHost = config.getString("ofx.http.host", "0.0.0.0");
        int ofxPort = config.getInt("ofx.http.port", 8082);
        String ofxUser = config.getString("ofx.user", "investor");
        String ofxPassword = config.getString("ofx.password", "secret");
        String brokerId = config.getString("ofx.brokerId", "FXC-BROKER");
        String devAccount = config.getString("account.dev", "000123456");

        // Cold-data archival to MariaDB (best-effort — runs without it if the DB is unreachable).
        ColdStore coldStore = openColdStore(config);
        long archiveIntervalMs = config.getInt("archive.intervalMs", 30_000);

        System.out.println("FxcBroker starting (grid='" + gridInstance + "', exchange="
                + exchangeHost + ":" + exchangePort + " as " + senderCompId + ", OFX :" + ofxPort
                + ", archival " + (coldStore != null ? "every " + archiveIntervalMs + "ms" : "off") + ")...");

        BrokerServer server = BrokerServer.start(
                gridInstance, gridDiscoveryPort, workDir,
                FixSettingsFactory.initiator(exchangeHost, exchangePort, senderCompId, "EXCHANGE"),
                ofxHost, ofxPort, ofxUser, ofxPassword, brokerId,
                accounts -> accounts.seedAccount(devAccount, "Dev Investor", "USD",
                        Map.of("USD", new BigDecimal("1000000"))),
                dropCopyEnabled
                        ? FixSettingsFactory.initiator(pubHost, pubPort, senderCompId, "FXCPUB")
                        : null,
                coldStore, archiveIntervalMs);

        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("FxcBroker stopping...");
            server.close();
            shutdown.countDown();
        }));
        System.out.println("FxcBroker started. OFX on port " + server.ofxPort()
                + ", account " + devAccount + " seeded. Ctrl-C to stop.");
        shutdown.await();
    }

    private static ColdStore openColdStore(FxcConfig config) {
        if (!config.getBoolean("archive.enabled", true)) {
            return null;
        }
        String url = config.getString("db.url", "jdbc:mariadb://localhost:3306/fxc_broker");
        String user = config.getString("db.user", "fxc");
        String password = config.getString("db.password", "fxc");
        try {
            return ColdStore.open(url, user, password, "fxc-broker-cold", "db/schema.sql");
        } catch (Exception e) {
            System.out.println("Cold-data archival unavailable (" + e.getMessage() + "); continuing without it.");
            return null;
        }
    }

    private static FxcConfig loadConfig() {
        Path confFile = Path.of("conf", "fxcbroker.conf");
        return Files.exists(confFile) ? FxcConfig.load(confFile) : FxcConfig.empty();
    }
}
