package com.fxc.common.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The hand-rolled JSON writer is now shared by every component's REST surface, so its escaping and
 * number formatting are pinned here. Number form matters to the consoles: prices must not arrive in
 * exponent notation, and trailing zeros are preserved because {@code BigDecimal} scale carries the
 * instrument's tick precision.
 */
class JsonTest {

    @Test
    void escapesStringsThatWouldBreakAPayload() {
        assertEquals("\"EUR/USD\"", Json.str("EUR/USD"));
        assertEquals("\"say \\\"hi\\\"\"", Json.str("say \"hi\""));
        assertEquals("\"back\\\\slash\"", Json.str("back\\slash"));
        assertEquals("\"a\\nb\"", Json.str("a\nb"));
        assertEquals("\"a\\rb\"", Json.str("a\rb"));
        assertEquals("\"a\\tb\"", Json.str("a\tb"));
        assertEquals("\"\"", Json.str(""));
    }

    @Test
    void escapesOtherControlCharactersAsUnicode() {
        assertEquals("\"\\u0000\"", Json.str("\u0000"));
        assertEquals("\"\\u001f\"", Json.str("\u001f"));
        // 0x20 is a printable space and must stay literal.
        assertEquals("\" \"", Json.str(" "));
    }

    @Test
    void emitsPlainNumbersWithScalePreserved() {
        assertEquals("42.00", Json.num(new BigDecimal("42.00")));
        assertEquals("1.08425", Json.num(new BigDecimal("1.08425")));
        assertEquals("0", Json.num(BigDecimal.ZERO));
        assertEquals("-3.5", Json.num(new BigDecimal("-3.5")));
        assertEquals("null", Json.num(null));
    }

    @Test
    void neverUsesExponentNotation() {
        // new BigDecimal("1E+3").toString() is "1E+3", which is not valid JSON.
        assertEquals("1000", Json.num(new BigDecimal("1E+3")));
        assertEquals("0.00000001", Json.num(new BigDecimal("1E-8")));
    }

    @Test
    void buildsArrays() {
        assertEquals("[]", Json.array(List.of(), Json::str));
        assertEquals("[\"a\"]", Json.array(List.of("a"), Json::str));
        assertEquals("[\"a\",\"b\",\"c\"]", Json.array(List.of("a", "b", "c"), Json::str));
        assertEquals("[{\"v\":1},{\"v\":2}]",
                Json.array(List.of(1, 2), i -> "{\"v\":" + i + "}"));
    }
}
