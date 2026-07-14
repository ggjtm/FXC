package com.fxc.exchange.feed;

import com.fxc.common.store.ColdStore;
import java.util.function.LongSupplier;
import org.apache.ignite.Ignite;

/**
 * Assembles the exchange feed service (FxcExchange/docs/stories/001): the {@link CandleService}
 * (REST history), the {@link WebSocketFeedServer} + {@link LiveFeed} (live one-second ticker
 * windows), and the {@link FeedHttpServer} (REST + charting UI). One object owns their lifecycle;
 * {@link ExchangeServer} registers {@link #liveFeed()} as a matching-engine listener.
 */
public final class FeedService implements AutoCloseable {

    private final WebSocketFeedServer wsServer;
    private final LiveFeed liveFeed;
    private final FeedHttpServer httpServer;

    private FeedService(WebSocketFeedServer wsServer, LiveFeed liveFeed, FeedHttpServer httpServer) {
        this.wsServer = wsServer;
        this.liveFeed = liveFeed;
        this.httpServer = httpServer;
    }

    /**
     * Start all feed transports. {@code httpPort}/{@code wsPort} may be 0 to bind an ephemeral port
     * (tests); the bound ports are available via {@link #httpPort()} / {@link #wsPort()}.
     */
    public static FeedService start(Ignite ignite, ColdStore cold, LongSupplier clock,
                                    String host, int httpPort, int wsPort) throws Exception {
        WebSocketFeedServer ws = new WebSocketFeedServer(host, wsPort);
        ws.start();
        LiveFeed live = new LiveFeed(ws, clock);
        live.start();
        CandleService candles = new CandleService(ignite, cold, clock);
        FeedHttpServer http = new FeedHttpServer(host, httpPort, candles, ws.boundPort());
        http.start();
        return new FeedService(ws, live, http);
    }

    /** Register this with {@code MatchingEngineService.addListener} to receive trades. */
    public LiveFeed liveFeed() {
        return liveFeed;
    }

    public int httpPort() {
        return httpServer.boundPort();
    }

    public int wsPort() {
        return wsServer.boundPort();
    }

    @Override
    public void close() {
        httpServer.close();
        liveFeed.close();
        wsServer.close();
    }
}
