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
 * <p>{@code bookfish} is additionally wrapped in {@link PatientStrategy} (docs/stories/003), so it
 * waits for an advantage instead of falling back to a uniform draw when it has too little traded
 * volume to form a view. <b>Patience goes inside liquidity</b>: the liquidity policy must be able to
 * size the intent patience approved, and because it declines whenever its delegate declines, an
 * abstention only ever delays a forced liquidity sell — the gate is stateless and re-asked every tick.
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
            case "bookfish" -> liquidityManaged(
                    patient(new SamplingStrategy("bookfish", new BookfishSampler())));
            default -> throw new IllegalArgumentException("unknown strategy: " + name
                    + " (available: rando, booker, bookfish)");
        };
    }

    private static Strategy liquidityManaged(Strategy delegate) {
        return new LiquidityAwareStrategy(delegate);
    }

    private static Strategy patient(Strategy delegate) {
        return new PatientStrategy(delegate);
    }
}
