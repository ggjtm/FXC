package com.fxc.exchange.feed;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Pure OHLCV aggregation (FxcExchange/docs/stories/001): buckets trades into candles and sums volume
 * at each price. No GridGain / I/O — the candle math is unit-testable in isolation; {@link
 * CandleService} supplies the trade points.
 */
public final class CandleAggregator {

    private CandleAggregator() {
    }

    /**
     * Aggregate trades into OHLCV candles of {@code bucketMs} width. Buckets are aligned to epoch
     * multiples of {@code bucketMs} (so a day bucket starts at UTC midnight). Empty buckets are
     * omitted — the candle list is sparse. {@code trades} need not be sorted.
     */
    public static List<Candle> aggregate(List<TradePoint> trades, long bucketMs) {
        if (bucketMs <= 0) {
            throw new IllegalArgumentException("bucketMs must be positive");
        }
        // bucketStart -> mutable accumulator, ordered by bucket time.
        TreeMap<Long, Acc> byBucket = new TreeMap<>();
        for (TradePoint t : trades) {
            long bucketStart = Math.floorDiv(t.ts(), bucketMs) * bucketMs;
            byBucket.computeIfAbsent(bucketStart, b -> new Acc()).add(t);
        }
        List<Candle> candles = new ArrayList<>(byBucket.size());
        for (var e : byBucket.entrySet()) {
            candles.add(e.getValue().toCandle(e.getKey()));
        }
        return candles;
    }

    /** Sum traded volume at each distinct price, ascending by price. */
    public static List<PriceVolume> volumeByPrice(List<TradePoint> trades) {
        TreeMap<BigDecimal, BigDecimal> byPrice = new TreeMap<>();
        for (TradePoint t : trades) {
            byPrice.merge(t.price(), t.qty(), BigDecimal::add);
        }
        List<PriceVolume> out = new ArrayList<>(byPrice.size());
        byPrice.forEach((price, vol) -> out.add(new PriceVolume(price, vol)));
        return out;
    }

    /** Volume summed at one price point (for the UI's right-side volume-by-price histogram). */
    public record PriceVolume(BigDecimal price, BigDecimal volume) {
    }

    /**
     * Mutable per-bucket accumulator. Open is the price of the earliest trade in the bucket and
     * close the latest, tracked by timestamp so input order does not matter. On equal timestamps the
     * first trade seen wins for open and the last seen wins for close — a harmless tie-break.
     */
    private static final class Acc {
        private long firstTs = Long.MAX_VALUE;
        private long lastTs = Long.MIN_VALUE;
        private BigDecimal open;
        private BigDecimal close;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal volume = BigDecimal.ZERO;

        void add(TradePoint t) {
            if (t.ts() < firstTs) {
                firstTs = t.ts();
                open = t.price();
            }
            if (t.ts() >= lastTs) {
                lastTs = t.ts();
                close = t.price();
            }
            high = (high == null || t.price().compareTo(high) > 0) ? t.price() : high;
            low = (low == null || t.price().compareTo(low) < 0) ? t.price() : low;
            volume = volume.add(t.qty());
        }

        Candle toCandle(long bucketStart) {
            return new Candle(bucketStart, open, high, low, close, volume);
        }
    }
}
