package com.fxc.exchange.feed;

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

    private final WebSocketFeedServer ws;
    private final LongSupplier clock;
    private final Map<String, List<TradePoint>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fxc-livefeed");
        t.setDaemon(true);
        return t;
    });

    public LiveFeed(WebSocketFeedServer ws, LongSupplier clock) {
        this.ws = ws;
        this.clock = clock;
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

    /** Drain each symbol's buffered trades and publish a one-second tick window. */
    void flush() {
        long windowStart = Math.floorDiv(clock.getAsLong(), WINDOW_MS) * WINDOW_MS;
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
            ws.publish(symbol, tickJson(symbol, windowStart, drained));
        }
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
