package com.fxc.exchange.feed;

import com.fxc.common.web.Json;
import com.fxc.exchange.book.Trade;
import com.fxc.exchange.feed.CandleAggregator.PriceVolume;
import com.fxc.exchange.service.ExchangeEvent;
import com.fxc.exchange.service.ExchangeListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The exchange's live ticker feed (FxcExchange/docs/stories/001): an {@link ExchangeListener} that
 * buffers executed trades and, on a fixed one-second cadence, flushes each symbol's trades as an
 * aggregated tick window — last sale plus volume grouped and summed by price — to the
 * {@link WebSocketFeedServer}. This is the minimum one-second windowing the story specifies; the
 * charting UI folds these windows into the current live candle.
 */
public final class LiveFeed implements ExchangeListener, AutoCloseable {

    private static final long WINDOW_MS = 1_000L;
    /** How long a socket may stay silent before a heartbeat is sent. */
    private static final long HEARTBEAT_MS = 5_000L;
    /** Seconds of history behind the {@link #tradesPerSec()} rate shown on the console. */
    private static final int RATE_WINDOW_SECONDS = 10;

    private final WebSocketFeedServer ws;
    private final LongSupplier clock;
    private final Map<String, List<TradePoint>> pending = new ConcurrentHashMap<>();
    private final long[] rateRing = new long[RATE_WINDOW_SECONDS];
    private int rateIndex;
    private volatile double tradesPerSec;
    private volatile long totalTrades;
    private long lastSendMs;
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fxc-livefeed");
        t.setDaemon(true);
        return t;
    });

    public LiveFeed(WebSocketFeedServer ws, LongSupplier clock) {
        this.ws = ws;
        this.clock = clock;
        // Seed the heartbeat clock so the first quiet window does not fire one immediately.
        this.lastSendMs = clock.getAsLong();
    }

    public void start() {
        flusher.scheduleWithFixedDelay(this::flush, WINDOW_MS, WINDOW_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onEvent(ExchangeEvent event) {
        if (event.trades().isEmpty()) {
            return;
        }
        List<TradePoint> bucket = pending.computeIfAbsent(event.symbol(), s -> new ArrayList<>());
        synchronized (bucket) {
            for (Trade trade : event.trades()) {
                bucket.add(new TradePoint(event.ts(), trade.price(), trade.quantity()));
            }
        }
    }

    /**
     * Drain each symbol's buffered trades and publish a one-second tick window. A window with no
     * trades publishes nothing for that symbol; if the whole feed has been silent for
     * {@value #HEARTBEAT_MS} ms a heartbeat goes out instead, so a client can distinguish a quiet
     * market from a dead socket.
     */
    void flush() {
        long now = clock.getAsLong();
        long windowStart = Math.floorDiv(now, WINDOW_MS) * WINDOW_MS;
        long tradesThisWindow = 0;
        for (String symbol : pending.keySet()) {
            List<TradePoint> bucket = pending.get(symbol);
            if (bucket == null) {
                continue;
            }
            List<TradePoint> drained;
            synchronized (bucket) {
                if (bucket.isEmpty()) {
                    continue;
                }
                drained = new ArrayList<>(bucket);
                bucket.clear();
            }
            tradesThisWindow += drained.size();
            ws.publish(symbol, tickJson(symbol, windowStart, drained));
            lastSendMs = now;
        }

        recordRate(tradesThisWindow);

        if (now - lastSendMs >= HEARTBEAT_MS) {
            ws.broadcast("{\"type\":\"heartbeat\",\"ts\":" + now + "}");
            lastSendMs = now;
        }
    }

    /** Roll the per-second trade counts and recompute the published rate. */
    private void recordRate(long tradesThisWindow) {
        totalTrades += tradesThisWindow;
        rateRing[rateIndex] = tradesThisWindow;
        rateIndex = (rateIndex + 1) % rateRing.length;
        long sum = 0;
        for (long n : rateRing) {
            sum += n;
        }
        tradesPerSec = (double) sum / rateRing.length;
    }

    /** Executed trades per second, averaged over the last {@value #RATE_WINDOW_SECONDS} seconds. */
    public double tradesPerSec() {
        return tradesPerSec;
    }

    /** Trades seen since start — the console's cumulative counter. */
    public long totalTrades() {
        return totalTrades;
    }

    private static String tickJson(String symbol, long windowStart, List<TradePoint> trades) {
        List<PriceVolume> byPrice = CandleAggregator.volumeByPrice(trades);
        TradePoint last = trades.get(0);
        java.math.BigDecimal totalVol = java.math.BigDecimal.ZERO;
        for (TradePoint t : trades) {
            if (t.ts() >= last.ts()) {
                last = t;
            }
            totalVol = totalVol.add(t.qty());
        }
        return "{\"type\":\"tick\",\"symbol\":" + Json.str(symbol)
                + ",\"windowStart\":" + windowStart
                + ",\"last\":" + Json.num(last.price())
                + ",\"volume\":" + Json.num(totalVol)
                + ",\"byPrice\":" + Json.array(byPrice,
                        pv -> "{\"price\":" + Json.num(pv.price()) + ",\"volume\":" + Json.num(pv.volume()) + "}")
                + "}";
    }

    @Override
    public void close() {
        flusher.shutdownNow();
    }
}
