package com.fxc.exchange.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fxc.exchange.feed.CandleAggregator.PriceVolume;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure OHLCV aggregation, volume-by-price, and the age-based granularity floors. No infra. */
class CandleAggregatorTest {

    private static TradePoint t(long ts, String price, String qty) {
        return new TradePoint(ts, new BigDecimal(price), new BigDecimal(qty));
    }

    @Test
    void bucketsTradesIntoOhlcvAlignedToEpochMultiples() {
        long m = Granularities.MINUTE_MS;
        // Two minute-buckets. Bucket 0: [0, 60s); bucket 1: [60s, 120s).
        List<Candle> candles = CandleAggregator.aggregate(List.of(
                t(1_000, "42.00", "3"),      // open of bucket 0
                t(30_000, "42.50", "1"),     // high
                t(20_000, "41.80", "2"),     // low
                t(59_000, "42.10", "4"),     // close of bucket 0
                t(61_000, "43.00", "5"),     // single trade in bucket 1
                t(90_000, "43.20", "2")      // close of bucket 1, and its high
        ), m);

        assertEquals(2, candles.size());
        Candle b0 = candles.get(0);
        assertEquals(0, b0.startMs());
        assertEquals(new BigDecimal("42.00"), b0.open());
        assertEquals(new BigDecimal("42.50"), b0.high());
        assertEquals(new BigDecimal("41.80"), b0.low());
        assertEquals(new BigDecimal("42.10"), b0.close());
        assertEquals(new BigDecimal("10"), b0.volume());

        Candle b1 = candles.get(1);
        assertEquals(m, b1.startMs());
        assertEquals(new BigDecimal("43.00"), b1.open());
        assertEquals(new BigDecimal("43.20"), b1.high());
        assertEquals(new BigDecimal("43.00"), b1.low());
        assertEquals(new BigDecimal("43.20"), b1.close());
        assertEquals(new BigDecimal("7"), b1.volume());
    }

    @Test
    void openCloseTrackByTimestampNotInputOrder() {
        long m = Granularities.MINUTE_MS;
        // Deliberately shuffled input within one bucket.
        List<Candle> candles = CandleAggregator.aggregate(List.of(
                t(40_000, "42.30", "1"),
                t(10_000, "42.00", "1"),   // earliest -> open
                t(55_000, "42.90", "1"),   // latest -> close
                t(25_000, "42.10", "1")
        ), m);
        assertEquals(1, candles.size());
        assertEquals(new BigDecimal("42.00"), candles.get(0).open());
        assertEquals(new BigDecimal("42.90"), candles.get(0).close());
    }

    @Test
    void emptyBucketsAreOmitted() {
        long m = Granularities.MINUTE_MS;
        List<Candle> candles = CandleAggregator.aggregate(List.of(
                t(1_000, "10", "1"),
                t(3 * m + 500, "11", "1")   // bucket 3; buckets 1,2 empty
        ), m);
        assertEquals(2, candles.size());
        assertEquals(0, candles.get(0).startMs());
        assertEquals(3 * m, candles.get(1).startMs());
    }

    @Test
    void volumeByPriceSumsPerPriceAscending() {
        List<PriceVolume> hist = CandleAggregator.volumeByPrice(List.of(
                t(1, "42.10", "3"),
                t(2, "42.00", "1"),
                t(3, "42.10", "2"),
                t(4, "42.00", "4")
        ));
        assertEquals(2, hist.size());
        assertEquals(new BigDecimal("42.00"), hist.get(0).price());
        assertEquals(new BigDecimal("5"), hist.get(0).volume());
        assertEquals(new BigDecimal("42.10"), hist.get(1).price());
        assertEquals(new BigDecimal("5"), hist.get(1).volume());
    }

    @Test
    void granularityFloorsByWindowAge() {
        long now = 1_000_000_000_000L;
        long minute = Granularities.MINUTE_MS;

        // Recent window: 1-minute request honored.
        assertEquals(minute,
                Granularities.effectiveGranularity(minute, now - Granularities.HOUR_MS, now));

        // Touches > 1 week old: floored to 1 day even if 1m requested.
        assertEquals(Granularities.DAY_MS,
                Granularities.effectiveGranularity(minute, now - 8L * Granularities.DAY_MS, now));

        // Touches > 6 months old: floored to 1 week.
        assertEquals(Granularities.WEEK_MS,
                Granularities.effectiveGranularity(minute, now - 200L * Granularities.DAY_MS, now));

        // A coarser request than the floor is left as requested.
        assertEquals(Granularities.WEEK_MS,
                Granularities.effectiveGranularity(Granularities.WEEK_MS, now - Granularities.HOUR_MS, now));
    }

    @Test
    void parsesGranularityTokens() {
        assertEquals(Granularities.MINUTE_MS, Granularities.parse("1m"));
        assertEquals(Granularities.FIFTEEN_MIN_MS, Granularities.parse("15m"));
        assertEquals(Granularities.HOUR_MS, Granularities.parse("1h"));
        assertEquals(Granularities.DAY_MS, Granularities.parse("1d"));
        assertEquals(Granularities.WEEK_MS, Granularities.parse("1w"));
    }
}
