package com.fxc.exchange.feed;

/**
 * Candle-granularity constants and the age-based minimum-granularity policy from
 * FxcExchange/docs/stories/001: to bound feed workload, a history that touches data older than one
 * week may be requested at no finer than one-day candles, and one touching data older than six
 * months at no finer than one-week candles. The overall floor is one minute.
 */
public final class Granularities {

    public static final long MINUTE_MS = 60_000L;
    public static final long FIVE_MIN_MS = 5 * MINUTE_MS;
    public static final long FIFTEEN_MIN_MS = 15 * MINUTE_MS;
    public static final long THIRTY_MIN_MS = 30 * MINUTE_MS;
    public static final long HOUR_MS = 60 * MINUTE_MS;
    public static final long FOUR_HOUR_MS = 4 * HOUR_MS;
    public static final long DAY_MS = 24 * HOUR_MS;
    public static final long WEEK_MS = 7 * DAY_MS;

    /** Six months, approximated as 182 days — the threshold need not be calendar-exact. */
    public static final long SIX_MONTHS_MS = 182 * DAY_MS;

    private Granularities() {
    }

    /**
     * The minimum candle granularity permitted for a window, given how old its oldest edge is.
     * The window's oldest point is at {@code startMs}; its age is {@code now - startMs}.
     *
     * @return {@link #WEEK_MS} if the window touches data older than six months, {@link #DAY_MS} if
     *         it touches data older than one week, else {@link #MINUTE_MS}.
     */
    public static long minGranularityFor(long startMs, long now) {
        long age = now - startMs;
        if (age > SIX_MONTHS_MS) {
            return WEEK_MS;
        }
        if (age > WEEK_MS) {
            return DAY_MS;
        }
        return MINUTE_MS;
    }

    /**
     * The granularity actually used for a request: the requested size raised to the age-based floor
     * (and never finer than one minute).
     */
    public static long effectiveGranularity(long requestedMs, long startMs, long now) {
        long floor = minGranularityFor(startMs, now);
        long requested = Math.max(requestedMs, MINUTE_MS);
        return Math.max(requested, floor);
    }

    /** Parse a granularity token like {@code 1m, 5m, 15m, 30m, 1h, 4h, 1d, 1w} into millis. */
    public static long parse(String token) {
        if (token == null || token.isBlank()) {
            return MINUTE_MS;
        }
        String t = token.trim().toLowerCase();
        char unit = t.charAt(t.length() - 1);
        long n = Long.parseLong(t.substring(0, t.length() - 1));
        return switch (unit) {
            case 'm' -> n * MINUTE_MS;
            case 'h' -> n * HOUR_MS;
            case 'd' -> n * DAY_MS;
            case 'w' -> n * WEEK_MS;
            default -> throw new IllegalArgumentException("bad granularity: " + token);
        };
    }
}
