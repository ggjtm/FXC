package com.fxc.exchange;

import com.fxc.common.config.FxcConfig;
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

        System.out.println("FxcExchange starting (GridGain='" + instanceName + "', FIX acceptor from cfg)...");
        ExchangeServer server = ExchangeServer.start(
                settings, instanceName, discoveryPort, workDir, InstrumentCatalog.defaults());

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

    private static FxcConfig loadConfig() {
        Path confFile = Path.of("conf", "fxcexchange.conf");
        return Files.exists(confFile) ? FxcConfig.load(confFile) : FxcConfig.empty();
    }
}
