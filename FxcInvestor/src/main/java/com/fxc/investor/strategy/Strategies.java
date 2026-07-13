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
            case "booker" -> new SamplingStrategy("booker", new BookerSampler());
            case "bookfish" -> new SamplingStrategy("bookfish", new BookfishSampler());
            default -> throw new IllegalArgumentException("unknown strategy: " + name
                    + " (available: rando, booker, bookfish)");
        };
    }
}
