package com.fxc.investor.strategy;

/**
 * Selects a {@link Strategy} by name (docs/stories/). {@code rando} is implemented; {@code booker}
 * and {@code bookfish} are specified as stories and will register their samplers here.
 */
public final class Strategies {

    private Strategies() {
    }

    public static Strategy byName(String name) {
        return switch (name == null ? "rando" : name.toLowerCase()) {
            case "rando" -> new SamplingStrategy("rando", new RandoSampler());
            // ToDo (docs/stories/002,003): "booker" -> book-weighted sampler (≤1σ),
            //                              "bookfish" -> traded-volume sampler (≤0.5σ).
            default -> throw new IllegalArgumentException("unknown strategy: " + name
                    + " (available: rando; booker/bookfish are planned — see FxcInvestor/docs/stories/)");
        };
    }
}
