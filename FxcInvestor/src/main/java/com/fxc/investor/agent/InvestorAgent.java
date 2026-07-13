package com.fxc.investor.agent;

import com.fxc.common.instrument.Instrument;
import com.fxc.common.instrument.InstrumentCatalog;
import com.fxc.investor.ofx.OfxBrokerClient;
import com.fxc.investor.strategy.MarketView;
import com.fxc.investor.strategy.OrderIntent;
import com.fxc.investor.strategy.PortfolioView;
import com.fxc.investor.strategy.Strategy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives one autonomous investor (docs/DESIGN.md §4.4, docs/stories/004): each {@link #step} builds
 * the market/portfolio view, asks the {@link Strategy} for a decision, snaps the price to the
 * instrument tick size, and submits the order over OFX. Deterministic given a seeded RNG.
 */
public final class InvestorAgent {

    private final String account;
    private final OfxBrokerClient broker;
    private final Strategy strategy;
    private final MarketView marketView;
    private final Random rng;
    private final String clOrdPrefix;
    private final AtomicLong clSeq = new AtomicLong();

    public InvestorAgent(String account, OfxBrokerClient broker, Strategy strategy,
                         MarketView marketView, Random rng, String clOrdPrefix) {
        this.account = account;
        this.broker = broker;
        this.strategy = strategy;
        this.marketView = marketView;
        this.rng = rng;
        this.clOrdPrefix = clOrdPrefix;
    }

    public MarketView marketView() {
        return marketView;
    }

    /**
     * Run one decision tick for a symbol. Returns the submitted order, or empty if the strategy
     * declined (e.g. no market signal yet).
     */
    public Optional<SubmittedOrder> step(String symbol, PortfolioView portfolio) throws Exception {
        Optional<OrderIntent> decision = strategy.decide(symbol, marketView, portfolio, rng);
        if (decision.isEmpty()) {
            return Optional.empty();
        }
        OrderIntent intent = decision.get();
        BigDecimal price = snapToTick(symbol, intent.price());
        String clOrdId = clOrdPrefix + "-" + clSeq.incrementAndGet();
        String status = broker.submitOrder(account, clOrdId, symbol,
                intent.side(), price, intent.quantity());
        return Optional.of(new SubmittedOrder(clOrdId, intent, price, status));
    }

    /** Snap a raw price to the instrument's tick grid. */
    private static BigDecimal snapToTick(String symbol, BigDecimal price) {
        Instrument instrument = InstrumentCatalog.find(symbol)
                .orElseThrow(() -> new IllegalArgumentException("unknown instrument: " + symbol));
        BigDecimal tick = instrument.tickSize();
        return price.divide(tick, 0, RoundingMode.HALF_UP).multiply(tick).setScale(tick.scale(), RoundingMode.HALF_UP);
    }
}
