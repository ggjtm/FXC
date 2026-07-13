package com.fxc.investor.cli;

import com.fxc.common.instrument.Instrument;
import com.fxc.common.instrument.InstrumentCatalog;
import com.fxc.investor.agent.InvestorAgent;
import com.fxc.investor.agent.SubmittedOrder;
import com.fxc.investor.feed.FeedClient;
import com.fxc.investor.ofx.OfxBrokerClient;
import com.fxc.investor.store.DecisionRecord;
import com.fxc.investor.store.InvestorStore;
import com.fxc.investor.strategy.MarketView;
import com.fxc.investor.strategy.PortfolioView;
import com.fxc.investor.strategy.Side;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Interactive CLI REPL for FxcInvestor (docs/DESIGN.md §4.4, docs/stories/004): a thin interactive
 * face over the OFX client, XMPP feed, autonomous agent loop, and decision-log store.
 *
 * <p>Commands: {@code buy}/{@code sell} {@code <symbol> <qty> <price>}, {@code positions},
 * {@code orders}, {@code feed}, {@code post <text>}, {@code agent on|off}, {@code help},
 * {@code quit}.
 */
public final class Repl {

    private final OfxBrokerClient broker;
    private final MarketView market;
    private final InvestorAgent agent;
    private final FeedClient feed;       // nullable
    private final InvestorStore store;   // nullable
    private final String account;
    private final String defaultSymbol;
    private final String strategyName;
    private final String ownFeedId;      // where `post` publishes
    private final long agentIntervalMs;

    private final AtomicLong cliSeq = new AtomicLong();
    private final Deque<String> recentOrders = new ArrayDeque<>();
    private volatile boolean agentOn = false;
    private volatile boolean running = true;

    public Repl(OfxBrokerClient broker, MarketView market, InvestorAgent agent, FeedClient feed,
                InvestorStore store, String account, String defaultSymbol, String strategyName,
                String ownFeedId, long agentIntervalMs) {
        this.broker = broker;
        this.market = market;
        this.agent = agent;
        this.feed = feed;
        this.store = store;
        this.account = account;
        this.defaultSymbol = defaultSymbol;
        this.strategyName = strategyName;
        this.ownFeedId = ownFeedId;
        this.agentIntervalMs = agentIntervalMs;
    }

    public void run() throws Exception {
        System.out.println("FxcInvestor REPL — account " + account + ", strategy " + strategyName
                + (feed != null ? ", feed on" : ", feed off") + (store != null ? ", persistence on" : ""));
        printHelp();

        Thread agentThread = new Thread(this::agentLoop, "fxc-agent");
        agentThread.setDaemon(true);
        agentThread.start();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (running) {
            System.out.print("fxc> ");
            System.out.flush();
            String line = in.readLine();
            if (line == null) {
                break; // EOF
            }
            try {
                dispatch(line.trim());
            } catch (Exception e) {
                System.out.println("error: " + e.getMessage());
            }
        }
        running = false;
        System.out.println("bye.");
    }

    private void dispatch(String line) throws Exception {
        if (line.isEmpty()) {
            return;
        }
        String[] parts = line.split("\\s+");
        String cmd = parts[0].toLowerCase();
        switch (cmd) {
            case "help", "?" -> printHelp();
            case "buy" -> order(Side.BUY, parts);
            case "sell" -> order(Side.SELL, parts);
            case "positions", "pos" -> positions();
            case "orders" -> orders();
            case "feed" -> showFeed();
            case "post" -> post(line);
            case "agent" -> toggleAgent(parts);
            case "quit", "exit" -> running = false;
            default -> System.out.println("unknown command '" + cmd + "' (try 'help')");
        }
    }

    private void order(Side side, String[] parts) throws Exception {
        if (parts.length < 4) {
            System.out.println("usage: " + side.name().toLowerCase() + " <symbol> <qty> <price>");
            return;
        }
        String symbol = parts[1];
        BigDecimal qty = new BigDecimal(parts[2]);
        BigDecimal price = snapToTick(symbol, new BigDecimal(parts[3]));
        submit(side, symbol, price, qty, "manual");
    }

    private void submit(Side side, String symbol, BigDecimal price, BigDecimal qty, String source) throws Exception {
        String clOrdId = "CLI-" + cliSeq.incrementAndGet();
        String status = broker.submitOrder(account, clOrdId, symbol, side, price, qty);
        String line = side + " " + qty + " " + symbol + " @ " + price + " -> " + status + " (" + clOrdId + ")";
        System.out.println("  " + line);
        pushOrder(line);
        logDecision(new DecisionRecord(System.currentTimeMillis(), account, symbol, source.equals("manual")
                ? "manual" : strategyName, side.name(), qty, price, clOrdId, status));
    }

    private void positions() throws Exception {
        PortfolioView portfolio = broker.fetchPortfolio(account);
        System.out.println("  cash:");
        for (Map.Entry<String, BigDecimal> e : portfolio.cashByCurrency().entrySet()) {
            System.out.println("    " + e.getKey() + " " + e.getValue());
        }
        System.out.println("  shares:");
        if (portfolio.shares().isEmpty()) {
            System.out.println("    (none)");
        }
        for (Map.Entry<String, BigDecimal> e : portfolio.shares().entrySet()) {
            System.out.println("    " + e.getKey() + " " + e.getValue());
        }
    }

    private void orders() {
        synchronized (recentOrders) {
            if (recentOrders.isEmpty()) {
                System.out.println("  (no orders this session)");
            }
            recentOrders.forEach(o -> System.out.println("  " + o));
        }
    }

    private void showFeed() {
        market.lastSale(defaultSymbol).ifPresent(p -> System.out.println("  last sale " + defaultSymbol + ": " + p));
        if (feed == null) {
            System.out.println("  (feed unavailable)");
            return;
        }
        List<String> statuses = feed.recentStatuses(10);
        if (statuses.isEmpty()) {
            System.out.println("  (no feed activity yet)");
        }
        statuses.forEach(s -> System.out.println("  " + s));
    }

    private void post(String line) throws Exception {
        String text = line.length() > 4 ? line.substring(4).trim() : "";
        if (text.isEmpty()) {
            System.out.println("usage: post <text>");
            return;
        }
        if (feed == null) {
            System.out.println("  (feed unavailable — cannot post)");
            return;
        }
        feed.publishStatus(ownFeedId, text);
        System.out.println("  posted to " + FeedClient.feedNode(ownFeedId));
    }

    private void toggleAgent(String[] parts) {
        if (parts.length < 2) {
            System.out.println("agent is " + (agentOn ? "on" : "off") + " (usage: agent on|off)");
            return;
        }
        agentOn = "on".equalsIgnoreCase(parts[1]);
        System.out.println("  agent " + (agentOn ? "on" : "off"));
    }

    private void agentLoop() {
        while (running) {
            try {
                if (agentOn) {
                    if ("booker".equals(strategyName)) {
                        try {
                            market.setBook(defaultSymbol, broker.requestBook(defaultSymbol, 10));
                        } catch (Exception ignore) {
                            // book relay unavailable; booker falls back to rando
                        }
                    }
                    Optional<SubmittedOrder> submitted = agent.step(defaultSymbol, PortfolioView.empty());
                    submitted.ifPresent(o -> {
                        String line = "[agent] " + o.intent().side() + " " + o.intent().quantity() + " "
                                + defaultSymbol + " @ " + o.snappedPrice() + " -> " + o.status();
                        System.out.println("\n" + line);
                        System.out.print("fxc> ");
                        System.out.flush();
                        pushOrder(line);
                        logDecision(new DecisionRecord(System.currentTimeMillis(), account, defaultSymbol,
                                strategyName, o.intent().side().name(), o.intent().quantity(),
                                o.snappedPrice(), o.clOrdId(), o.status()));
                    });
                }
                Thread.sleep(agentIntervalMs);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                System.out.println("[agent] error: " + e.getMessage());
            }
        }
    }

    private void pushOrder(String line) {
        synchronized (recentOrders) {
            recentOrders.addLast(line);
            while (recentOrders.size() > 20) {
                recentOrders.removeFirst();
            }
        }
    }

    private void logDecision(DecisionRecord record) {
        if (store != null) {
            try {
                store.logDecision(record);
            } catch (Exception e) {
                System.out.println("  (failed to persist decision: " + e.getMessage() + ")");
            }
        }
    }

    private static BigDecimal snapToTick(String symbol, BigDecimal price) {
        Instrument instrument = InstrumentCatalog.find(symbol)
                .orElseThrow(() -> new IllegalArgumentException("unknown instrument: " + symbol));
        BigDecimal tick = instrument.tickSize();
        return price.divide(tick, 0, RoundingMode.HALF_UP).multiply(tick).setScale(tick.scale(), RoundingMode.HALF_UP);
    }

    private void printHelp() {
        System.out.println("""
                commands:
                  buy  <symbol> <qty> <price>   submit a buy order over OFX
                  sell <symbol> <qty> <price>   submit a sell order over OFX
                  positions                     show cash + share positions (OFX statement)
                  orders                        show this session's orders
                  feed                          show recent feed statuses + last sale
                  post <text>                   post a status to your feed
                  agent on|off                  toggle the autonomous strategy loop
                  help                          this help
                  quit                          exit""");
    }
}
