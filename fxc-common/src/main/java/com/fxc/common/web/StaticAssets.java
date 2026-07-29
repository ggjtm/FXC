package com.fxc.common.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Serves a component's web console from the classpath (docs/DESIGN.md §6): an index page at
 * {@code /} plus any number of mounted asset trees. Replaces the single hard-coded page the
 * exchange's feed UI started with, so a console can be split into HTML + CSS + JS and can pull
 * shared assets (the dark theme, the dropdown, the vendored D3) out of the {@code fxc-common} jar.
 *
 * <p>Resources are immutable for a JVM's lifetime, so each is read once, cached with a content-hash
 * {@code ETag}, and afterwards answered from memory — an {@code If-None-Match} match returns 304.
 * That matters: the vendored D3 bundle is ~280 KB and would otherwise be re-read and re-allocated on
 * every request.
 *
 * <p>Mount points are configured before the server starts and not changed afterwards; the asset
 * cache is thread-safe.
 */
public final class StaticAssets implements HttpHandler {

    /** Conservative allowlist: no path traversal, no encoded surprises, no absolute paths. */
    private static final Pattern SAFE_PATH = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]*");

    private static final Map<String, String> MIME = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "application/javascript; charset=utf-8",
            "json", "application/json; charset=utf-8",
            "svg", "image/svg+xml",
            "ico", "image/x-icon",
            "png", "image/png",
            "woff2", "font/woff2");

    private final String indexResource;
    /** URL prefix -> classpath resource prefix, longest-first at lookup time. */
    private final Map<String, String> mounts = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, Asset> cache = new ConcurrentHashMap<>();

    /** @param indexResource classpath resource served at {@code /} and {@code /index.html} */
    public StaticAssets(String indexResource) {
        this.indexResource = indexResource;
    }

    /**
     * Mount an asset tree. Both prefixes must end in {@code /}; a request to
     * {@code <urlPrefix>x/y.js} resolves to classpath {@code <resourcePrefix>x/y.js}.
     * Call before starting the server.
     */
    public StaticAssets mount(String urlPrefix, String resourcePrefix) {
        if (!urlPrefix.endsWith("/") || !resourcePrefix.endsWith("/")) {
            throw new IllegalArgumentException("mount prefixes must end with '/'");
        }
        mounts.put(urlPrefix, resourcePrefix);
        return this;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        // Resolve before gating the method. This handler is mounted on "/" as a catch-all, so it
        // also receives requests for API paths that are not registered (for example the control
        // endpoints when they are switched off). Answering those 405 "method not allowed" would
        // imply the endpoint exists and was merely called wrongly; they must read as 404.
        String resource = resolve(ex.getRequestURI().getPath());
        if (resource == null) {
            HttpJson.sendText(ex, 404, "not found");
            return;
        }
        if (HttpJson.requireMethod(ex, "GET")) {
            return;
        }
        Asset asset = cache.computeIfAbsent(resource, StaticAssets::load);
        if (asset == null) {
            // An index that isn't on the classpath is a packaging fault, not a bad request.
            boolean isIndex = resource.equals(indexResource);
            HttpJson.sendText(ex, isIndex ? 500 : 404, isIndex ? "UI resource missing" : "not found");
            return;
        }
        if (asset.etag.equals(ex.getRequestHeaders().getFirst("If-None-Match"))) {
            ex.getResponseHeaders().set("ETag", asset.etag);
            HttpJson.send(ex, 304, null, new byte[0]);
            return;
        }
        ex.getResponseHeaders().set("ETag", asset.etag);
        // no-cache = "may cache, but revalidate" — assets change between builds, ETags make that cheap.
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        HttpJson.send(ex, 200, asset.contentType, asset.bytes);
    }

    /** Map a request path to a classpath resource, or null if it addresses nothing we serve. */
    String resolve(String path) {
        if (path.equals("/") || path.equals("/index.html")) {
            return indexResource;
        }
        for (Map.Entry<String, String> mount : mounts.entrySet()) {
            String urlPrefix = mount.getKey();
            if (!path.startsWith(urlPrefix)) {
                continue;
            }
            String rest = path.substring(urlPrefix.length());
            if (rest.isEmpty() || rest.contains("..") || rest.contains("//") || !SAFE_PATH.matcher(rest).matches()) {
                return null;
            }
            return mount.getValue() + rest;
        }
        return null;
    }

    /** Read and hash a resource, or null when it is not on the classpath (never cached). */
    private static Asset load(String resource) {
        try (InputStream in = StaticAssets.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            byte[] bytes = in.readAllBytes();
            return new Asset(bytes, etag(bytes), contentType(resource));
        } catch (IOException e) {
            return null;
        }
    }

    static String contentType(String resource) {
        int dot = resource.lastIndexOf('.');
        String ext = dot < 0 ? "" : resource.substring(dot + 1).toLowerCase(Locale.ROOT);
        return MIME.getOrDefault(ext, "application/octet-stream");
    }

    private static String etag(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder b = new StringBuilder(18).append('"');
            for (int i = 0; i < 8; i++) {
                b.append(String.format("%02x", digest[i]));
            }
            return b.append('"').toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK; fall back to a length tag rather than failing a page load.
            return "\"len-" + bytes.length + "\"";
        }
    }

    private record Asset(byte[] bytes, String etag, String contentType) {
    }
}
