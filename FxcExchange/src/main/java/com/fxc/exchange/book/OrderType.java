package com.fxc.exchange.book;

/** Order type. Market remainders that cannot fill are cancelled (immediate-or-cancel behavior). */
public enum OrderType {
    LIMIT,
    MARKET
}
