package com.fxc.broker;

import com.fxc.broker.account.AccountOpeningPolicy;
import com.fxc.broker.oms.FixSettingsFactory;
import com.fxc.broker.pnl.PnlService;
import com.fxc.broker.pnl.PnlSettings;
import com.fxc.common.config.FxcConfig;
import com.fxc.common.instrument.InstrumentCatalog;
import com.fxc.common.store.ColdStore;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        // Two dev accounts (PLAN Phase 6): both seeded with cash AND shares so buys and sells are
        // fundable and can cross each other in the demo.
        String devAccount = config.getString("account.dev", "000123456");
        String devAccount2 = config.getString("account.dev2", "000654321");
        BigDecimal devCash = new BigDecimal(config.getString("account.seedCash", "1000000"));
        // "*" (the default) = every listed equity, so the 25-name list never has to appear in a
        // conf file or a Salt pillar. A comma-separated list still pins a subset for a narrower demo.
        List<String> seedSymbols = InstrumentCatalog.resolveSymbols(
                config.getString("account.seedSymbol", "*"));
        // Blank (the default) = each symbol's own catalog reference price. A single scalar would mark
        // twenty-five different companies at one price, which is what made every book look identical.
        String seedPriceOverride = config.getString("account.seedSharePrice", "").strip();
        // The tradable float, created ONCE by an issuer and placed with the market makers, rather than
        // conjured per account (docs/PROBLEMS.md P19). The issuer is what makes the total a number
        // somebody chose instead of a by-product of how many accounts happen to exist.
        String issuerAccount = config.getString("account.issuer", "000000001");
        BigDecimal issuedShares = new BigDecimal(config.getString("account.issue.shares", "1000000"));
        BigDecimal makerShares = new BigDecimal(config.getString("account.mm.shares", "500000"));

        // Agents open their own accounts (docs/stories/004). Funded with CASH ONLY by default: an
        // opened account that also carried shares would mint them, and adding investors to a running
        // demo would inflate the float rather than bring capital to it — 260 agents injected 260,000
        // shares against a 2,000-share float and the price fell 23% (docs/PROBLEMS.md P19). The float
        // is what the seeded dev accounts hold; investors bring money to bid for it.
        boolean accountsEnabled = config.getBoolean("account.open.enabled", true);
        // Not defaulted to the market makers' balance: an investor is retail-sized next to a desk
        // holding the float, and inheriting devCash silently made them equals.
        BigDecimal openCash = new BigDecimal(config.getString("account.open.seedCash", "100000"));
        BigDecimal openShares = new BigDecimal(config.getString("account.open.seedShares", "20"));
        List<String> openSymbols = InstrumentCatalog.resolveSymbols(
                config.getString("account.open.seedSymbol", "*"));
        // Opened accounts draw their shares FROM the issuer's reserve rather than minting them, so
        // the float stays a number somebody chose no matter how many investors spawn.
        String openFrom = config.getString("account.open.seedFrom", issuerAccount);
        AccountOpeningPolicy openingPolicy = new AccountOpeningPolicy(accountsEnabled, "USD", openCash,
                openSymbols, openShares,
                seedPriceOverride.isBlank() ? null : new BigDecimal(seedPriceOverride), openFrom,
                Long.parseLong(config.getString("account.open.first", "100000")),
                config.getInt("account.open.numberWidth", 9));

        // Fail before BrokerServer.start opens a listener: with 25 symbols an over-allocation now
        // dies on symbol 4 of 25, leaving a half-seeded market behind a live port.
        BigDecimal reservePerSymbol = issuedShares.subtract(makerShares.multiply(BigDecimal.valueOf(2)));
        if (reservePerSymbol.signum() < 0) {
            throw new IllegalStateException("account.mm.shares (" + makerShares.toPlainString()
                    + " x2) exceeds account.issue.shares (" + issuedShares.toPlainString()
                    + ") per symbol; the float cannot be over-allocated");
        }

        // Rolling P&L window for the console (docs/stories/003).
        PnlSettings pnlSettings = new PnlSettings(
                Long.parseLong(config.getString("pnl.windowMs",
                        String.valueOf(PnlService.DEFAULT_WINDOW_MS))),
                config.getInt("pnl.maxPointsPerAccount", PnlService.DEFAULT_MAX_POINTS_PER_ACCOUNT),
                config.getInt("pnl.groupSize", PnlService.DEFAULT_GROUP_SIZE),
                config.getInt("pnl.maxRetainedPoints", PnlService.DEFAULT_MAX_RETAINED_POINTS),
                Long.parseLong(config.getString("pnl.internalAccountsBelow",
                        String.valueOf(PnlService.DEFAULT_INTERNAL_ACCOUNTS_BELOW))));

        // Monitor/controller console (FxcBroker/docs/stories/002).
        boolean webEnabled = config.getBoolean("web.enabled", true);
        int webPort = webEnabled ? config.getInt("web.http.port", 8083) : -1;
        boolean webControls = config.getBoolean("web.controls.enabled", true);
        // The ceiling on OFX throughput. Four is plenty for the demo's own agents; the Locust harness
        // can outrun it, so it is configurable rather than baked in.
        int ofxThreads = config.getInt("ofx.http.threads",
                com.fxc.broker.ofx.OfxHttpServer.DEFAULT_THREADS);

        // Cold-data archival to MariaDB (best-effort — runs without it if the DB is unreachable).
        ColdStore coldStore = openColdStore(config);
        long archiveIntervalMs = config.getInt("archive.intervalMs", 30_000);

        System.out.println("FxcBroker starting (grid='" + gridInstance + "', exchange="
                + exchangeHost + ":" + exchangePort + " as " + senderCompId + ", OFX :" + ofxPort
                + ", OFX threads " + ofxThreads
                + ", console " + (webEnabled ? ":" + webPort
                        + " controls " + (webControls ? "on" : "off") : "off")
                + ", account opening " + (accountsEnabled ? "on" : "off")
                + ", archival " + (coldStore != null ? "every " + archiveIntervalMs + "ms" : "off") + ")...");

        BrokerServer server = BrokerServer.start(
                gridInstance, gridDiscoveryPort, workDir,
                FixSettingsFactory.initiator(exchangeHost, exchangePort, senderCompId, "EXCHANGE"),
                ofxHost, ofxPort, ofxUser, ofxPassword, brokerId,
                accounts -> {
                    accounts.configureOpening(openingPolicy);
                    String[] makers = {devAccount, devAccount2};
                    // Accounts first, OUTSIDE the symbol loop: seedAccount re-writes the USD cash
                    // position, so calling it per symbol would reset each maker's cash 25 times.
                    accounts.seedAccount(issuerAccount, "FXC Issuer", "USD", Map.of());
                    for (String acct : makers) {
                        accounts.seedAccount(acct, "Market Maker " + acct, "USD",
                                Map.of("USD", devCash));
                    }
                    // 1. The issuer creates the float, once per listed symbol. Shares exist here and
                    //    nowhere else. 2. It places most of that float with the market makers — a
                    //    transfer, not a second seeding — and keeps the remainder as the reserve
                    //    opened accounts draw from. Per symbol the total stays exactly what was issued.
                    for (String symbol : seedSymbols) {
                        BigDecimal basis = seedPriceOverride.isBlank()
                                ? InstrumentCatalog.referencePrice(symbol).orElseThrow()
                                : new BigDecimal(seedPriceOverride);
                        accounts.seedShares(issuerAccount, symbol, issuedShares, basis);
                        for (String acct : makers) {
                            accounts.transferShares(issuerAccount, acct, symbol, makerShares);
                        }
                    }
                },
                dropCopyEnabled
                        ? FixSettingsFactory.initiator(pubHost, pubPort, senderCompId, "FXCPUB")
                        : null,
                coldStore, archiveIntervalMs, webPort, webControls, ofxThreads, pnlSettings,
                accountsEnabled);

        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("FxcBroker stopping...");
            server.close();
            shutdown.countDown();
        }));
        System.out.println("FxcBroker started. OFX on port " + server.ofxPort()
                + ", " + seedSymbols.size() + " symbols x " + issuedShares.toPlainString()
                + " shares issued by " + issuerAccount + " (" + makerShares.toPlainString()
                + " each to " + devAccount + "/" + devAccount2 + ", "
                + reservePerSymbol.toPlainString() + " reserved per symbol)."
                + (webEnabled ? " Console: http://localhost:" + server.webServer().boundPort() + "/" : "")
                + " Ctrl-C to stop.");
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
