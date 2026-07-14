package com.fxc.exchange.feed;

import com.fxc.common.store.ColdStore;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.query.SqlFieldsQuery;

/**
 * Serves aggregated price history for the exchange feed service (FxcExchange/docs/stories/001).
 * Reads executed trades from the GridGain {@code TRADE} hot table and, when a {@link ColdStore} is
 * configured, unions the archived {@code TRADE_ARCHIVE} rows — so history survives cold archival —
 * then buckets them into OHLCV candles at the effective granularity (raised to the age-based floors
 * in {@link Granularities}) and computes the volume-by-price histogram.
 */
public final class CandleService {

    private final IgniteCache<?, ?> sql;
    private final ColdStore cold;      // nullable
    private final LongSupplier clock;

    public CandleService(Ignite ignite, ColdStore cold, LongSupplier clock) {
        this.sql = ignite.getOrCreateCache("fxc-sql-entry");
        this.cold = cold;
        this.clock = clock;
    }

    /** Traded symbols, ascending — the feed UI's symbol selector. */
    public List<String> symbols() {
        List<String> out = new ArrayList<>();
        for (List<?> r : sql.query(new SqlFieldsQuery("SELECT symbol FROM INSTRUMENT ORDER BY symbol")).getAll()) {
            out.add((String) r.get(0));
        }
        return out;
    }

    /**
     * Aggregate the price history for {@code symbol} over {@code [startMs, endMs)}. The requested
     * granularity is raised to the age-based floor; the response reports the granularity actually
     * used. Trades are read hot + cold and combined.
     */
    public CandleResponse candles(String symbol, long startMs, long endMs, long requestedGranularityMs) {
        long effGran = Granularities.effectiveGranularity(requestedGranularityMs, startMs, clock.getAsLong());
        List<TradePoint> trades = tradesInWindow(symbol, startMs, endMs);
        List<Candle> candles = CandleAggregator.aggregate(trades, effGran);
        return new CandleResponse(symbol, startMs, endMs, effGran, candles,
                CandleAggregator.volumeByPrice(trades));
    }

    /** Read trades for a symbol in {@code [startMs, endMs)} from the hot table and cold archive. */
    private List<TradePoint> tradesInWindow(String symbol, long startMs, long endMs) {
        List<TradePoint> out = new ArrayList<>();
        for (List<?> r : sql.query(new SqlFieldsQuery(
                "SELECT ts, price, quantity FROM TRADE WHERE symbol = ? AND ts >= ? AND ts < ? ORDER BY ts")
                .setArgs(symbol, startMs, endMs)).getAll()) {
            out.add(new TradePoint(((Number) r.get(0)).longValue(), (BigDecimal) r.get(1), (BigDecimal) r.get(2)));
        }
        if (cold != null) {
            addColdTrades(out, symbol, startMs, endMs);
        }
        return out;
    }

    private void addColdTrades(List<TradePoint> out, String symbol, long startMs, long endMs) {
        String q = "SELECT ts, price, quantity FROM TRADE_ARCHIVE WHERE symbol = ? AND ts >= ? AND ts < ? ORDER BY ts";
        try (Connection c = cold.connection(); PreparedStatement ps = c.prepareStatement(q)) {
            ps.setString(1, symbol);
            ps.setLong(2, startMs);
            ps.setLong(3, endMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TradePoint(rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3)));
                }
            }
        } catch (SQLException e) {
            // Cold history is best-effort; a cold-store hiccup must not break the hot feed.
            System.err.println("Cold trade read failed for " + symbol + ": " + e.getMessage());
        }
    }
}
