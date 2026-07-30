package com.fxc.investor.strategy;

import com.fxc.common.instrument.Instrument;
import com.fxc.common.instrument.InstrumentCatalog;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Random;

/**
 * Makes {@code bookfish} wait for an advantage instead of always submitting (docs/stories/003).
 *
 * <p><b>Why.</b> {@code bookfish} samples a price from the histogram of volume actually traded. When
 * that histogram is thin, {@link HistogramSampling#sample} falls back to a uniform ±1% draw around the
 * last sale — which is {@code rando} wearing {@code bookfish}'s name, and indistinguishable from it in
 * a decision log. A strategy whose whole premise is "trade where volume trades" should <b>decline</b>
 * in that situation, not guess.
 *
 * <p><b>What it does</b>, in order:
 * <ol>
 *   <li><b>Readiness.</b> The traded-volume histogram must support a distribution at all (the cases
 *       {@link HistogramSampling#stats} returns {@code null} for) <em>and</em> carry at least
 *       {@code minVolume} of observed volume. Otherwise decline, reason {@value #NOT_READY}.</li>
 *   <li><b>Edge.</b> Fair value is the volume-weighted mean price of that histogram — the same centre
 *       the sampler draws around, which is why both read it from {@code stats}. The drawn target must
 *       sit at least {@code minEdgeTicks} on the <em>favourable</em> side of fair value for the drawn
 *       side: below it to buy, above it to sell. Otherwise decline, reason {@value #NO_EDGE}.</li>
 * </ol>
 *
 * <p><b>The draw happens first, always.</b> The delegate samples price <em>and</em> side/quantity
 * before either gate is consulted, so declining consumes exactly the same random numbers as trading.
 * Whether {@code bookfish} is patient therefore cannot shift the rest of a seeded sequence.
 *
 * <p><b>Stateless.</b> No cooldown, no memory of past abstentions: the decision is re-made every tick
 * against fresh data. That is what keeps patience from starving {@link LiquidityAwareStrategy}, which
 * wraps <em>this</em> and declines whenever its delegate declines — a forced liquidity sell is delayed
 * by at most one tick's worth of abstention, never prevented.
 *
 * <p>Implemented twice, here and in Python {@code fxc_loadgen.patience}, with matching tests, for the
 * reason {@link LiquidityAwareStrategy} gives: a strategy must not mean two different things in two
 * languages.
 */
public final class PatientStrategy implements Strategy {

    /** Observed traded volume required before the histogram is trusted at all. */
    public static final BigDecimal DEFAULT_MIN_VOLUME = new BigDecimal("50");

    /** Ticks of advantage against fair value required to trade; {@code 0} disables the edge gate. */
    public static final int DEFAULT_MIN_EDGE_TICKS = 1;

    /** Not enough traded volume to form a view. */
    public static final String NOT_READY = "not-ready";
    /** A view, but the drawn price offers no advantage for the drawn side. */
    public static final String NO_EDGE = "no-edge";
    /** The delegate itself had nothing to say (no last sale yet). */
    public static final String NO_OPINION = "no-opinion";

    private final Strategy delegate;
    private final BigDecimal minVolume;
    private final int minEdgeTicks;

    /** Why the last decision declined, or {@code null} if it traded. Diagnostic only. */
    private volatile String lastReason;

    public PatientStrategy(Strategy delegate) {
        this(delegate, DEFAULT_MIN_VOLUME, DEFAULT_MIN_EDGE_TICKS);
    }

    public PatientStrategy(Strategy delegate, BigDecimal minVolume, int minEdgeTicks) {
        this.delegate = delegate;
        this.minVolume = minVolume;
        this.minEdgeTicks = minEdgeTicks;
    }

    /**
     * Why the most recent {@link #decide} declined, or {@code null} if it produced an order.
     *
     * <p>Diagnostic: the agent's decision log records {@code SKIPPED} without a reason, the same as
     * before. The Python harness surfaces this as a per-reason stats row because a load-test operator
     * needs to tell a patient strategy from a broken one at a glance.
     */
    public String lastReason() {
        return lastReason;
    }

    @Override
    public Optional<OrderIntent> decide(String symbol, MarketView market, PortfolioView portfolio,
                                        Random rng) {
        // Delegate first, unconditionally: the gates must not change what is drawn, only what is sent.
        Optional<OrderIntent> drawn = delegate.decide(symbol, market, portfolio, rng);
        if (drawn.isEmpty()) {
            lastReason = NO_OPINION;
            return Optional.empty();
        }

        HistogramSampling.Stats stats = HistogramSampling.stats(market.tradedVolume(symbol));
        if (stats == null || stats.totalWeight() < minVolume.doubleValue()) {
            lastReason = NOT_READY;
            return Optional.empty();
        }

        if (minEdgeTicks > 0) {
            Instrument instrument = InstrumentCatalog.find(symbol).orElse(null);
            if (instrument == null) {
                // No tick size to express the margin in; decline rather than invent one.
                lastReason = NOT_READY;
                return Optional.empty();
            }
            BigDecimal margin = instrument.tickSize().multiply(BigDecimal.valueOf(minEdgeTicks));
            BigDecimal fairValue = BigDecimal.valueOf(stats.mean());
            OrderIntent intent = drawn.get();
            BigDecimal edge = intent.side() == Side.BUY
                    ? fairValue.subtract(intent.price())
                    : intent.price().subtract(fairValue);
            if (edge.compareTo(margin) < 0) {
                lastReason = NO_EDGE;
                return Optional.empty();
            }
        }

        lastReason = null;
        return drawn;
    }
}
