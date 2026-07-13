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

    static BigDecimal sample(Map<BigDecimal, BigDecimal> histogram, BigDecimal lastSale,
                             double sigmaMult, double fallbackBand, Random rng) {
        // Stable, sorted bins with positive weight (determinism).
        List<Map.Entry<BigDecimal, BigDecimal>> bins = new ArrayList<>();
        for (Map.Entry<BigDecimal, BigDecimal> e : histogram.entrySet()) {
            if (e.getKey() != null && e.getValue() != null && e.getValue().signum() > 0) {
                bins.add(Map.entry(e.getKey(), e.getValue()));
            }
        }
        bins.sort(Map.Entry.comparingByKey());
        if (bins.size() < 2) {
            return fallback(lastSale, fallbackBand, rng);
        }

        double sumW = 0;
        double sumWP = 0;
        for (Map.Entry<BigDecimal, BigDecimal> e : bins) {
            double w = e.getValue().doubleValue();
            sumW += w;
            sumWP += w * e.getKey().doubleValue();
        }
        if (sumW <= 0) {
            return fallback(lastSale, fallbackBand, rng);
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
            return fallback(lastSale, fallbackBand, rng);
        }

        double band = sigmaMult * std;
        double last = lastSale.doubleValue();
        List<Map.Entry<BigDecimal, BigDecimal>> inBand = new ArrayList<>();
        double total = 0;
        for (Map.Entry<BigDecimal, BigDecimal> e : bins) {
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
