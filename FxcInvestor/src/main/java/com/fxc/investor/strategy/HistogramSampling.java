package com.fxc.investor.strategy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Weighted price-histogram sampling shared by {@code booker} and {@code bookfish}
 * (docs/stories/002,003). Draws a price from a {@code price -> weight} histogram, restricted to
 * within {@code sigmaMult} standard deviations of the last sale (σ computed over the
 * weight-weighted price distribution). Falls back to a uniform {@code ±fallbackBand} around the
 * last sale when the histogram is degenerate (empty, single price, or zero variance).
 */
final class HistogramSampling {

    private HistogramSampling() {
    }

    /**
     * The shape of a price histogram: its positive-weight bins, sorted, plus their weight-weighted
     * centre and spread.
     *
     * <p>Extracted so {@link PatientStrategy} reads the <em>same</em> numbers the sampler draws
     * around — "fair value" in the gate has to mean the same thing as the centre here, or the two
     * would disagree about what an advantage is. Mirrors Python {@code strategies.HistogramStats}.
     */
    record Stats(List<Map.Entry<BigDecimal, BigDecimal>> bins, double totalWeight, double mean,
                 double std) {
    }

    /**
     * Weighted stats for a {@code price -> weight} histogram, or {@code null} when it cannot support
     * a distribution: fewer than two positive-weight bins, non-positive total weight, or zero
     * variance (all the weight at one price). Those are exactly the cases
     * {@link #sample} falls back on.
     */
    static Stats stats(Map<BigDecimal, BigDecimal> histogram) {
        // Stable, sorted bins with positive weight (determinism).
        List<Map.Entry<BigDecimal, BigDecimal>> bins = new ArrayList<>();
        for (Map.Entry<BigDecimal, BigDecimal> e : histogram.entrySet()) {
            if (e.getKey() != null && e.getValue() != null && e.getValue().signum() > 0) {
                bins.add(Map.entry(e.getKey(), e.getValue()));
            }
        }
        bins.sort(Map.Entry.comparingByKey());
        if (bins.size() < 2) {
            return null;
        }

        double sumW = 0;
        double sumWP = 0;
        for (Map.Entry<BigDecimal, BigDecimal> e : bins) {
            double w = e.getValue().doubleValue();
            sumW += w;
            sumWP += w * e.getKey().doubleValue();
        }
        if (sumW <= 0) {
            return null;
        }
        double mean = sumWP / sumW;
        double var = 0;
        for (Map.Entry<BigDecimal, BigDecimal> e : bins) {
            double diff = e.getKey().doubleValue() - mean;
            var += e.getValue().doubleValue() * diff * diff;
        }
        var /= sumW;
        double std = Math.sqrt(var);
        if (std <= 0) {
            return null;
        }
        return new Stats(bins, sumW, mean, std);
    }

    static BigDecimal sample(Map<BigDecimal, BigDecimal> histogram, BigDecimal lastSale,
                             double sigmaMult, double fallbackBand, Random rng) {
        Stats stats = stats(histogram);
        if (stats == null) {
            return fallback(lastSale, fallbackBand, rng);
        }

        double band = sigmaMult * stats.std();
        double last = lastSale.doubleValue();
        List<Map.Entry<BigDecimal, BigDecimal>> inBand = new ArrayList<>();
        double total = 0;
        for (Map.Entry<BigDecimal, BigDecimal> e : stats.bins()) {
            if (Math.abs(e.getKey().doubleValue() - last) <= band) {
                inBand.add(e);
                total += e.getValue().doubleValue();
            }
        }
        if (inBand.isEmpty() || total <= 0) {
            return fallback(lastSale, fallbackBand, rng);
        }

        double r = rng.nextDouble() * total;
        double acc = 0;
        for (Map.Entry<BigDecimal, BigDecimal> e : inBand) {
            acc += e.getValue().doubleValue();
            if (r <= acc) {
                return e.getKey();
            }
        }
        return inBand.get(inBand.size() - 1).getKey();
    }

    private static BigDecimal fallback(BigDecimal lastSale, double band, Random rng) {
        double factor = 1.0 + (rng.nextDouble() * 2.0 - 1.0) * band;
        return lastSale.multiply(BigDecimal.valueOf(factor));
    }
}
