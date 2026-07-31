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

        // Price-data feed service (FxcExchange/docs/stories/001): REST + web UI + live WebSocket.
        boolean feedEnabled = config.getBoolean("feed.enabled", true);
        int feedHttpPort = feedEnabled ? config.getInt("feed.http.port", 8090) : -1;
        int feedWsPort = config.getInt("feed.ws.port", 8091);
        // Console controls (stories/002): halt/resume trading, clear the order book. Unauthenticated
        // by design for the demo — set feed.controls.enabled=false to serve a read-only console.
        boolean feedControls = config.getBoolean("feed.controls.enabled", true);
        // The session starts HALTED so a demo can be opened deliberately rather than arriving
        // mid-flight (FxcExchange/docs/stories/002). Config-only: ExchangeServer.start() still boots an
        // open market, so nothing embedded — every integration test — has to learn about this.
        boolean startClosed = config.getBoolean("session.startClosed", true);

        System.out.println("FxcExchange starting (GridGain='" + instanceName + "', FIX acceptor from cfg"
                + ", archival " + (coldStore != null ? "every " + archiveIntervalMs + "ms" : "off")
                + ", feed " + (feedEnabled ? "http :" + feedHttpPort + " ws :" + feedWsPort : "off")
                + ", controls " + (feedEnabled && feedControls ? "on" : "off")
                + ", session " + (startClosed ? "CLOSED at boot" : "open") + ")...");
        ExchangeServer server = ExchangeServer.start(
                settings, instanceName, discoveryPort, workDir, InstrumentCatalog.defaults(),
                coldStore, archiveIntervalMs, feedHttpPort, feedWsPort, feedControls);

        if (startClosed) {
            // Halted immediately after start, not atomically with it: the FIX acceptor is listening
            // for the few milliseconds in between. Nothing is connected then in a normal start — the
            // broker comes up afterwards — and threading a flag through four `start` overloads to close
            // a window nobody can reach was not worth it. Restarting the exchange under a live broker
            // is the one case where an order could squeeze through.
            server.controlService().halt(null);
            if (!feedEnabled || !feedControls) {
                // A market that starts closed with no control surface cannot be opened by anything.
                System.out.println("WARNING: session.startClosed=true but the console controls are "
                        + (feedEnabled ? "disabled (feed.controls.enabled=false)" : "off (feed.enabled=false)")
                        + " — nothing can open this market. Set session.startClosed=false, or enable "
                        + "the controls.");
            }
        }

        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("FxcExchange stopping...");
            server.close();
            shutdown.countDown();
        }));
        System.out.println("FxcExchange started. " + InstrumentCatalog.defaults().size()
                + " instruments listed. Market is "
                + server.controlService().status().marketState() + "."
                + (startClosed ? " Open it from the console's Controls menu, or"
                        + " POST /api/session/open." : "")
                + (feedEnabled ? " Console: http://localhost:" + server.feedService().httpPort() + "/" : "")
                + " Ctrl-C to stop.");
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
