package com.fxc.common.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

/**
 * Minimal JSON writer for the components' REST/WebSocket payloads — FXC has no JSON library
 * dependency (like the OFX/FIX layers it stays framework-free, see docs/DESIGN.md §4.1). Emits just
 * what the payloads need: objects, arrays, strings, numbers. Not a general-purpose serializer, and
 * deliberately write-only: the control APIs take query parameters rather than request bodies so no
 * parser is needed (docs/DESIGN.md §6).
 *
 * <p>Originally {@code com.fxc.exchange.feed.Json}; promoted here when FxcBroker gained a web UI.
 */
public final class Json {

    private Json() {
    }

    /** Escape and quote a JSON string value. */
    public static String str(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    /** A BigDecimal as a bare JSON number (plain string form, no exponent). */
    public static String num(BigDecimal n) {
        return n == null ? "null" : n.toPlainString();
    }

    /** A JSON array built by mapping each element to a JSON fragment. */
    public static <T> String array(List<T> items, Function<T, String> toJson) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(toJson.apply(items.get(i)));
        }
        return b.append(']').toString();
    }
}
