package com.fxc.investor;

import com.fxc.common.config.FxcConfig;
import com.fxc.common.instrument.InstrumentCatalog;
import com.fxc.investor.account.AccountClient;
import com.fxc.investor.agent.InvestorAgent;
import com.fxc.investor.agent.PortfolioCache;
import com.fxc.investor.agent.SubmittedOrder;
import com.fxc.investor.feed.FeedClient;
import com.fxc.investor.ofx.OfxBrokerClient;
import com.fxc.investor.strategy.MarketView;
import com.fxc.investor.strategy.PortfolioView;
import com.fxc.investor.store.DecisionRecord;
import com.fxc.investor.store.InvestorStore;
import com.fxc.investor.strategy.Strategies;
import com.fxc.investor.strategy.Strategy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * FxcInvestor: agent + client for FxcBroker and FxcPub (docs/DESIGN.md §4.4). This is the
 * single-instance runner (docs/stories/004): it drives one agent's decision loop against a broker
 * over OFX with a selected strategy.
 *
 * <p>Feed-driven market signal (XMPP) and the interactive REPL are still open (PLAN items 3–4); for
 * now the last-sale price is seeded from config and the loop runs headless.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        FxcConfig config = loadConfig();

        String ofxUrl = config.getString("ofx.broker.url", "http://localhost:8082/ofx");
        String ofxUser = config.getString("ofx.user", "investor");
        String ofxPassword = config.getString("ofx.password", "secret");
        String brokerId = config.getString("ofx.brokerId", "FXC-BROKER");
        // Each agent trades its OWN account (docs/stories/004): with `account` unset it asks the
        // broker to open one under a stable client id, so the console's per-account P&L is this
        // agent's rather than a blend of everything sharing a dev account. Setting `account`
        // explicitly still pins it — that is what the REPL and the integration tests do.
        String configuredAccount = config.getString("account", "").strip();
        // A key present but empty is how the conf says "unset" — FxcConfig would otherwise hand back
        // the empty string and the broker would refuse a blank client id.
        String clientId = config.getString("agent.clientId", "").strip();
        if (clientId.isBlank()) {
            clientId = "investor-" + config.getInt("agent.seed", 42);
        }
        String consoleUrl = config.getString("broker.console.url", "http://localhost:8083");
        String fallbackAccount = config.getString("account.fallback", "000000002");
        String account = resolveAccount(configuredAccount, clientId, consoleUrl, fallbackAccount);

        String strategyName = config.getString("agent.strategy", "rando");
        // "*" (the default) = every listed equity, so one resident agent quotes all 25 books rather
        // than leaving 24 of them without a maker. A comma list still pins a subset.
        List<String> symbols = InstrumentCatalog.resolveSymbols(config.getString("agent.symbol", "*"));
        // Blank (the default) = each symbol's own catalog reference price. Twenty-five companies do
        // not share a price, and pricing off the wrong last-sale puts orders nowhere near the book.
        String seedLastSaleOverride = config.getString("agent.seedLastSale", "").strip();
        long intervalMs = config.getInt("agent.intervalMs", 1000);
        int ticks = config.getInt("agent.ticks", 10); // 0 = run until interrupted
        long seed = config.getInt("agent.seed", 42);
        boolean enabled = config.getBoolean("agent.enabled", true);
        String mode = config.getString("mode", "headless"); // headless | repl

        String xmppUser = config.getString("xmpp.user", "investor");
        String feedBroker = config.getString("xmpp.feedBroker", "BROKER1");

        OfxBrokerClient broker = new OfxBrokerClient(ofxUrl, ofxUser, ofxPassword, brokerId);
        Strategy strategy = Strategies.byName(strategyName);
        MarketView market = new MarketView();
        // Initial fallback per symbol; the live feed updates them below.
        for (String s : symbols) {
            market.setLastSale(s, seedLastSaleOverride.isBlank()
                    ? InstrumentCatalog.referencePrice(s).orElseThrow()
                    : new BigDecimal(seedLastSaleOverride));
        }

        // Live market data from the FxcPub XMPP feed (best-effort — falls back to the seed if the
        // feed is unavailable). Populates last-sale (all agents) and traded volume (bookfish).
        FeedClient feed = null;
        if (config.getBoolean("xmpp.feed.enabled", true)) {
            String xmppHost = config.getString("xmpp.host", "localhost");
            int xmppPort = config.getInt("xmpp.port", 5222);
            String xmppDomain = config.getString("xmpp.domain", "fxc.local");
            String xmppPassword = config.getString("xmpp.password", "secret");
            try {
                feed = new FeedClient(xmppHost, xmppPort, xmppDomain);
                feed.connect(xmppUser, xmppPassword);
                feed.subscribeFeed(feedBroker, market);
                if ("repl".equalsIgnoreCase(mode)) {
                    feed.subscribeFeed(xmppUser, market); // own feed, so `post` round-trips into `feed`
                }
                System.out.println("Subscribed to XMPP feed " + FeedClient.feedNode(feedBroker)
                        + " at " + xmppHost + ":" + xmppPort);
            } catch (Exception e) {
                System.out.println("XMPP feed unavailable (" + e.getMessage()
                        + "); using seeded last-sale from the catalog");
                if (feed != null) {
                    feed.close();
                    feed = null;
                }
            }
        }
        final FeedClient feedRef = feed;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (feedRef != null) {
                feedRef.close();
            }
        }));

        InvestorAgent agent = new InvestorAgent(account, broker, strategy, market, new Random(seed), "INV");

        // MariaDB decision-log persistence (best-effort — runs without it if the DB is unavailable).
        InvestorStore store = openStore(config);
        final InvestorStore storeRef = store;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (storeRef != null) {
                storeRef.close();
            }
        }));

        // Interactive mode: hand off to the REPL (buy/sell/positions/orders/feed/post/agent/quit).
        if ("repl".equalsIgnoreCase(mode)) {
            new com.fxc.investor.cli.Repl(broker, market, agent, feed, store, account, symbols.get(0),
                    strategyName, xmppUser, intervalMs,
                    new PortfolioCache(broker::fetchPortfolio, account,
                            config.getInt("agent.portfolioRefreshMs", 5_000))).run();
            return;
        }

        System.out.println("FxcInvestor starting (strategy=" + strategyName + ", symbols="
                + (symbols.size() == 1 ? symbols.get(0) : symbols.size() + " listed")
                + ", account=" + account + ", agent " + (enabled ? "on" : "off")
                + ", persistence " + (store != null ? "on" : "off") + ")...");
        if (!enabled) {
            System.out.println("agent off — nothing to do. Set agent.enabled=true.");
            return;
        }

        // Real holdings, refreshed on an interval rather than per tick: a statement read is an OFX
        // round trip and the broker's OFX pool is four threads. Before this, both loops passed
        // PortfolioView.empty() and no strategy could see its own cash or shares.
        long portfolioRefreshMs = config.getInt("agent.portfolioRefreshMs", 5_000);
        PortfolioCache portfolio = new PortfolioCache(broker::fetchPortfolio, account, portfolioRefreshMs);

        boolean refreshBook = config.getBoolean("agent.refreshBook", "booker".equals(strategyName));
        int done = 0;
        while (ticks == 0 || done < ticks) {
            // Round-robin, not random: this agent is the coverage guarantee, and a random walk over
            // 25 books at one tick a second leaves individual books unquoted for minutes at a time.
            String symbol = symbols.get(done % symbols.size());
            if (refreshBook) {
                // Feed booker's order-book histogram from the broker's snapshot relay (best-effort).
                try {
                    market.setBook(symbol, broker.requestBook(symbol, 10));
                } catch (Exception e) {
                    // book relay unavailable; booker falls back to rando behavior
                }
            }
            Optional<SubmittedOrder> submitted = agent.step(symbol, portfolio.current());
            submitted.ifPresent(o -> System.out.println("  " + o.intent().side() + " "
                    + o.intent().quantity() + " " + symbol + " @ " + o.snappedPrice()
                    + " -> " + o.status() + " (" + o.clOrdId() + ")"));
            logDecision(store, account, symbol, strategyName, submitted);
            done++;
            Thread.sleep(intervalMs);
        }
        System.out.println("FxcInvestor finished " + done + " ticks.");
    }

    private static InvestorStore openStore(FxcConfig config) {
        if (!config.getBoolean("db.enabled", true)) {
            return null;
        }
        String dbUrl = config.getString("db.url", "jdbc:mariadb://localhost:3306/fxc_investor");
        String dbUser = config.getString("db.user", "fxc");
        String dbPassword = config.getString("db.password", "fxc");
        try {
            return InvestorStore.open(dbUrl, dbUser, dbPassword);
        } catch (Exception e) {
            System.out.println("Decision-log persistence unavailable (" + e.getMessage() + "); continuing without it.");
            return null;
        }
    }

    /**
     * Which account this agent trades: the configured one if there is one, otherwise the one the
     * broker opens for this client id, otherwise the shared fallback.
     *
     * <p>The fallback matters: a broker with account opening switched off, or a console that is not
     * running, should cost the agent its <em>private</em> account, not its ability to trade.
     */
    private static String resolveAccount(String configured, String clientId, String consoleUrl,
                                         String fallback) {
        if (!configured.isBlank()) {
            return configured;
        }
        return new AccountClient(consoleUrl).open(clientId, "Agent " + clientId)
                .map(result -> {
                    System.out.println("[account] " + (result.opened() ? "opened " : "reusing ")
                            + result.account() + " for " + clientId);
                    return result.account();
                })
                .orElseGet(() -> {
                    System.out.println("[account] falling back to the shared account " + fallback
                            + " — this agent's P&L will include anything else trading it");
                    return fallback;
                });
    }

    private static void logDecision(InvestorStore store, String account, String symbol, String strategy,
                                    Optional<SubmittedOrder> submitted) {
        if (store == null) {
            return;
        }
        try {
            DecisionRecord record = submitted
                    .map(o -> new DecisionRecord(System.currentTimeMillis(), account, symbol, strategy,
                            o.intent().side().name(), o.intent().quantity(), o.snappedPrice(),
                            o.clOrdId(), o.status()))
                    .orElseGet(() -> new DecisionRecord(System.currentTimeMillis(), account, symbol, strategy,
                            null, null, null, null, DecisionRecord.SKIPPED));
            store.logDecision(record);
        } catch (Exception e) {
            System.out.println("Failed to log decision: " + e.getMessage());
        }
    }

    private static FxcConfig loadConfig() {
        Path confFile = Path.of("conf", "fxcinvestor.conf");
        return Files.exists(confFile) ? FxcConfig.load(confFile) : FxcConfig.empty();
    }
}
