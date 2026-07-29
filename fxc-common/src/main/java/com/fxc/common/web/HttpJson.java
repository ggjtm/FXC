package com.fxc.common.web;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Request/response plumbing shared by the components' JDK {@code HttpServer} endpoints — no web
 * framework, matching the OFX transport (docs/DESIGN.md §4.1/§6). Extracted from FxcExchange's
 * {@code FeedHttpServer} when FxcBroker gained a web UI of its own.
 *
 * <p>Control endpoints are {@code POST} with query parameters and an empty body, so this class has
 * no request-body parsing and FXC needs no JSON parser (see {@link Json}).
 */
public final class HttpJson {

    private static final String JSON_TYPE = "application/json; charset=utf-8";
    private static final String TEXT_TYPE = "text/plain; charset=utf-8";

    private HttpJson() {
    }

    // --- path and method gating ---

    /**
     * Enforce that the request addresses exactly {@code path}, answering 404 otherwise.
     *
     * <p>Needed because {@code HttpServer} contexts match by longest prefix: a context registered at
     * {@code /api/book} also receives {@code /api/book/clear}. Without this, an endpoint would either
     * serve a path it does not own or report "method not allowed" for a path it never had — so this
     * is checked <b>before</b> {@link #requireMethod}, which would otherwise mask the 404.
     *
     * @return true when the request has been fully handled and the caller should return immediately
     */
    public static boolean requireExactPath(HttpExchange ex, String path) throws IOException {
        if (path.equals(ex.getRequestURI().getPath())) {
            return false;
        }
        sendText(ex, 404, "not found");
        return true;
    }

    // --- method gating ---

    /**
     * Enforce a request method, answering {@code OPTIONS} preflights and rejecting anything else
     * with 405.
     *
     * @return true when the request has been fully handled and the caller should return immediately
     */
    public static boolean requireMethod(HttpExchange ex, String method) throws IOException {
        String actual = ex.getRequestMethod();
        if (method.equalsIgnoreCase(actual)) {
            return false;
        }
        if ("OPTIONS".equalsIgnoreCase(actual)) {
            preflight(ex, method);
            return true;
        }
        sendText(ex, 405, "method not allowed");
        return true;
    }

    /** Answer a CORS preflight for an endpoint that accepts {@code method}. */
    public static void preflight(HttpExchange ex, String method) throws IOException {
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", method + ", OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
        ex.getResponseHeaders().set("Access-Control-Max-Age", "600");
        send(ex, 204, null, new byte[0]);
    }

    // --- responses ---

    public static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        send(ex, status, JSON_TYPE, json.getBytes(StandardCharsets.UTF_8));
    }

    /** A {@code {"error":"..."}} body with the given status. */
    public static void sendError(HttpExchange ex, int status, String message) throws IOException {
        sendJson(ex, status, "{\"error\":" + Json.str(message) + "}");
    }

    public static void sendText(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, TEXT_TYPE, body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Write a response with the shared CORS header. A {@code null} contentType sends no
     * {@code Content-Type} (for 204/304, which carry no body).
     */
    public static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        if (contentType != null) {
            ex.getResponseHeaders().set("Content-Type", contentType);
        }
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        // 204 and 304 must not declare a body length; -1 tells HttpServer to omit Content-Length.
        boolean bodyless = status == 204 || status == 304;
        ex.sendResponseHeaders(status, bodyless ? -1 : body.length);
        if (bodyless) {
            ex.close();
            return;
        }
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    // --- query parsing ---

    /** Decoded query parameters. Repeated keys keep the last occurrence. */
    public static Map<String, String> query(URI uri) {
        Map<String, String> out = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) {
            return out;
        }
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                out.put(kv.substring(0, eq), URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    /** A query value, or null when absent or blank. */
    public static String optional(Map<String, String> query, String key) {
        String value = query.get(key);
        return value == null || value.isBlank() ? null : value;
    }

    /** Parse a long query value, falling back to {@code dflt} when absent or malformed. */
    public static long parseLong(String s, long dflt) {
        if (s == null || s.isBlank()) {
            return dflt;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** Parse an int query value, falling back to {@code dflt} when absent or malformed. */
    public static int parseInt(String s, int dflt) {
        return (int) parseLong(s, dflt);
    }
}
