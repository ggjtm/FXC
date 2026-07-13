package com.fxc.exchange;

import com.fxc.common.config.FxcConfig;
import com.fxc.common.instrument.InstrumentCatalog;
import com.fxc.common.store.ColdStore;
import com.fxc.exchange.fix.ExchangeServer;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import quickfix.SessionSettings;

/**
 * FxcExchange: minimal market data, trade matching, and clearing.
 *
 * <p>Boots an embedded GridGain node, seeds the default instrument universe
 * ({@link InstrumentCatalog}), and starts the FIX 4.4 acceptor. Blocks until interrupted.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        FxcConfig config = loadConfig();
        String instanceName = config.getString("gridgain.instanceName", "fxc-exchange");
        int discoveryPort = config.getInt("gridgain.discoveryPort", 47500);
        String workDir = config.getString("gridgain.workDir",
                Path.of(System.getProperty("java.io.tmpdir"), "fxc-exchange-ignite").toString());

        SessionSettings settings;
        try (InputStream cfg = Main.class.getResourceAsStream("/quickfixj/exchange-acceptor.cfg")) {
            settings = new SessionSettings(cfg);
        }

        // Cold-data archival to MariaDB (best-effort — runs without it if the DB is unreachable).
        ColdStore coldStore = openColdStore(config);
        long archiveIntervalMs = config.getInt("archive.intervalMs", 30_000);

        System.out.println("FxcExchange starting (GridGain='" + instanceName + "', FIX acceptor from cfg"
                + ", archival " + (coldStore != null ? "every " + archiveIntervalMs + "ms" : "off") + ")...");
        ExchangeServer server = ExchangeServer.start(
                settings, instanceName, discoveryPort, workDir, InstrumentCatalog.defaults(),
                coldStore, archiveIntervalMs);

        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("FxcExchange stopping...");
            server.close();
            shutdown.countDown();
        }));
        System.out.println("FxcExchange started. " + InstrumentCatalog.defaults().size()
                + " instruments listed. Ctrl-C to stop.");
        shutdown.await();
    }

    private static ColdStore openColdStore(FxcConfig config) {
        if (!config.getBoolean("archive.enabled", true)) {
            return null;
        }
        String url = config.getString("db.url", "jdbc:mariadb://localhost:3306/fxc_exchange");
        String user = config.getString("db.user", "fxc");
        String password = config.getString("db.password", "fxc");
        try {
            return ColdStore.open(url, user, password, "fxc-exchange-cold", "db/schema.sql");
        } catch (Exception e) {
            System.out.println("Cold-data archival unavailable (" + e.getMessage() + "); continuing without it.");
            return null;
        }
    }

    private static FxcConfig loadConfig() {
        Path confFile = Path.of("conf", "fxcexchange.conf");
        return Files.exists(confFile) ? FxcConfig.load(confFile) : FxcConfig.empty();
    }
}
