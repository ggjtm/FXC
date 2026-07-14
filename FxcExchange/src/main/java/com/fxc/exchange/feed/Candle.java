package com.fxc.exchange.feed;

import java.math.BigDecimal;

/**
 * An OHLCV candle over one time bucket (FxcExchange/docs/stories/001): open/high/low/close prices
 * and summed volume for all trades whose timestamp falls in {@code [startMs, startMs + granularity)}.
 *
 * @param startMs bucket start, epoch millis (aligned to a multiple of the granularity)
 * @param open    first trade price in the bucket
 * @param high    highest trade price in the bucket
 * @param low     lowest trade price in the bucket
 * @param close   last trade price in the bucket
 * @param volume  summed traded quantity in the bucket
 */
public record Candle(long startMs, BigDecimal open, BigDecimal high, BigDecimal low,
                     BigDecimal close, BigDecimal volume) {
}
