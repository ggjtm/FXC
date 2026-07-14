package com.fxc.exchange.feed;

import com.fxc.exchange.feed.CandleAggregator.PriceVolume;
import java.util.List;

/**
 * The aggregated feed snapshot the REST service returns (FxcExchange/docs/stories/001): OHLCV
 * candles plus the volume-by-price histogram, for one symbol over {@code [start, end)} at the
 * {@code granularityMs} actually applied (which may be coarser than requested per the age-based
 * floors in {@link Granularities}).
 */
public record CandleResponse(String symbol, long start, long end, long granularityMs,
                             List<Candle> candles, List<PriceVolume> volumeByPrice) {
}
