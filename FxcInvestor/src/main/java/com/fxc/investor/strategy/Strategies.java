package com.fxc.investor.strategy;

/**
 * Selects a {@link Strategy} by name (docs/stories/).
 *
 * <p>{@code rando} is the <b>naive</b> strategy and is returned bare: it ignores the portfolio
 * entirely, exactly as story 001 specifies, and will eventually be rejected for insufficient cash or
 * shares.
 *
 * <p>{@code booker} and {@code bookfish} are the <b>non-naive</b> strategies and are wrapped in
 * {@link LiquidityAwareStrategy}, which scales their buying to available cash and sells assets to
 * maintain liquidity (docs/DESIGN.md §6). That wrapping is what lets the demo run continuously instead
 * of exhausting one side of the seeded account and degrading into a reject stream.
 *
 * <p>Tests that want to exercise a bare sampler construct {@link SamplingStrategy} directly, so the
 * naive behaviour stays independently verifiable.
 */
public final class Strategies {

    private Strategies() {
    }

    /** Strategy names that ignore the portfolio and are therefore not liquidity-managed. */
    public static boolean isNaive(String name) {
        return "rando".equalsIgnoreCase(name == null ? "rando" : name);
    }

    public static Strategy byName(String name) {
        return switch (name == null ? "rando" : name.toLowerCase()) {
            case "rando" -> new SamplingStrategy("rando", new RandoSampler());
            case "booker" -> liquidityManaged(new SamplingStrategy("booker", new BookerSampler()));
            case "bookfish" -> liquidityManaged(new SamplingStrategy("bookfish", new BookfishSampler()));
            default -> throw new IllegalArgumentException("unknown strategy: " + name
                    + " (available: rando, booker, bookfish)");
        };
    }

    private static Strategy liquidityManaged(Strategy delegate) {
        return new LiquidityAwareStrategy(delegate);
    }
}
