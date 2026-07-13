package com.fxc.investor;

import com.fxc.common.config.FxcConfig;
import com.fxc.investor.agent.InvestorAgent;
import com.fxc.investor.agent.SubmittedOrder;
import com.fxc.investor.ofx.OfxBrokerClient;
import com.fxc.investor.strategy.MarketView;
import com.fxc.investor.strategy.PortfolioView;
import com.fxc.investor.strategy.Strategies;
import com.fxc.investor.strategy.Strategy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
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
        String account = config.getString("account", "000123456");

        String strategyName = config.getString("agent.strategy", "rando");
        String symbol = config.getString("agent.symbol", "ACME");
        BigDecimal seedLastSale = new BigDecimal(config.getString("agent.seedLastSale", "42.10"));
        long intervalMs = config.getInt("agent.intervalMs", 1000);
        int ticks = config.getInt("agent.ticks", 10); // 0 = run until interrupted
        long seed = config.getInt("agent.seed", 42);
        boolean enabled = config.getBoolean("agent.enabled", true);

        OfxBrokerClient broker = new OfxBrokerClient(ofxUrl, ofxUser, ofxPassword, brokerId);
        Strategy strategy = Strategies.byName(strategyName);
        MarketView market = new MarketView();
        market.setLastSale(symbol, seedLastSale); // ToDo: replace with XMPP feed ingestion (PLAN item 3)

        InvestorAgent agent = new InvestorAgent(account, broker, strategy, market, new Random(seed), "INV");

        System.out.println("FxcInvestor starting (strategy=" + strategyName + ", symbol=" + symbol
                + ", account=" + account + ", agent " + (enabled ? "on" : "off") + ")...");
        if (!enabled) {
            System.out.println("agent off — nothing to do. Set agent.enabled=true.");
            return;
        }

        int done = 0;
        while (ticks == 0 || done < ticks) {
            Optional<SubmittedOrder> submitted = agent.step(symbol, PortfolioView.empty());
            submitted.ifPresent(o -> System.out.println("  " + o.intent().side() + " "
                    + o.intent().quantity() + " " + symbol + " @ " + o.snappedPrice()
                    + " -> " + o.status() + " (" + o.clOrdId() + ")"));
            done++;
            Thread.sleep(intervalMs);
        }
        System.out.println("FxcInvestor finished " + done + " ticks.");
    }

    private static FxcConfig loadConfig() {
        Path confFile = Path.of("conf", "fxcinvestor.conf");
        return Files.exists(confFile) ? FxcConfig.load(confFile) : FxcConfig.empty();
    }
}
