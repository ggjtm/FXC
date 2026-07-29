package com.fxc.common.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * Path-safety and caching behaviour of the shared static-asset handler (docs/DESIGN.md §6).
 * The mount points here address the real shipped assets, so these tests double as proof that the
 * shared console CSS/JS and the vendored D3 bundle are actually on the classpath.
 */
class StaticAssetsTest {

    private static final String INDEX = "web/test-index.html";

    private static StaticAssets assets() {
        return new StaticAssets(INDEX)
                .mount("/common/", "web/common/")
                .mount("/assets/", "web/nonexistent/");
    }

    // --- path resolution (the traversal guard) ---

    @Test
    void resolvesIndexAtRootAndIndexHtml() {
        StaticAssets a = assets();
        assertEquals(INDEX, a.resolve("/"));
        assertEquals(INDEX, a.resolve("/index.html"));
    }

    @Test
    void resolvesMountedAssetsIncludingNestedPaths() {
        StaticAssets a = assets();
        assertEquals("web/common/fxc.css", a.resolve("/common/fxc.css"));
        assertEquals("web/common/vendor/d3.v7.min.js", a.resolve("/common/vendor/d3.v7.min.js"));
    }

    @Test
    void rejectsTraversalAndOddPaths() {
        StaticAssets a = assets();
        assertNull(a.resolve("/common/../../etc/passwd"), "parent traversal must not resolve");
        assertNull(a.resolve("/common/..%2fsecret"), "escaped traversal must not resolve");
        assertNull(a.resolve("/common/a//b.js"), "empty path segment must not resolve");
        assertNull(a.resolve("/common//etc/passwd"), "absolute-looking path must not resolve");
        assertNull(a.resolve("/common/"), "a bare mount point addresses no file");
        assertNull(a.resolve("/common/we ird.js"), "spaces are outside the allowlist");
        assertNull(a.resolve("/nowhere/x.js"), "unmounted prefix must not resolve");
    }

    @Test
    void mountPrefixesMustEndInSlash() {
        StaticAssets a = new StaticAssets(INDEX);
        try {
            a.mount("/common", "web/common/");
            assertTrue(false, "expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("/"));
        }
    }

    @Test
    void mapsContentTypeByExtension() {
        assertTrue(StaticAssets.contentType("web/x.html").startsWith("text/html"));
        assertTrue(StaticAssets.contentType("web/x.css").startsWith("text/css"));
        assertTrue(StaticAssets.contentType("web/x.js").startsWith("application/javascript"));
        assertEquals("image/svg+xml", StaticAssets.contentType("web/x.svg"));
        assertEquals("application/octet-stream", StaticAssets.contentType("web/x.unknown"));
        assertEquals("application/octet-stream", StaticAssets.contentType("web/noextension"));
    }

    // --- served over HTTP ---

    @Test
    void servesIndexAssetsAndCacheValidators() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.createContext("/", assets());
        server.start();
        int port = server.getAddress().getPort();
        HttpClient client = HttpClient.newHttpClient();
        try {
            HttpResponse<String> index = get(client, port, "/");
            assertEquals(200, index.statusCode());
            assertTrue(index.headers().firstValue("Content-Type").orElse("").contains("text/html"));
            assertTrue(index.body().contains("StaticAssetsTest fixture"));

            HttpResponse<String> css = get(client, port, "/common/fxc.css");
            assertEquals(200, css.statusCode());
            assertTrue(css.headers().firstValue("Content-Type").orElse("").contains("text/css"));
            assertTrue(css.body().contains("--series-1"), "shared theme should define the categorical slots");

            // The vendored D3 bundle must be reachable and typed as JavaScript.
            HttpResponse<String> d3 = get(client, port, "/common/vendor/d3.v7.min.js");
            assertEquals(200, d3.statusCode());
            assertTrue(d3.headers().firstValue("Content-Type").orElse("").contains("application/javascript"));
            assertTrue(d3.body().contains("d3js.org"), "expected the real d3 bundle");

            // ETag round-trip: a matching If-None-Match is answered 304 with no body.
            String etag = css.headers().firstValue("ETag").orElse(null);
            assertNotNull(etag, "assets must carry an ETag so a 280 KB bundle is not resent");
            HttpResponse<String> revalidated = client.send(
                    HttpRequest.newBuilder(uri(port, "/common/fxc.css")).header("If-None-Match", etag).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(304, revalidated.statusCode());
            assertTrue(revalidated.body().isEmpty());

            HttpResponse<String> stale = client.send(
                    HttpRequest.newBuilder(uri(port, "/common/fxc.css")).header("If-None-Match", "\"nope\"").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, stale.statusCode(), "a non-matching validator must re-send the body");

            // Misses and rejected paths are 404 — never a directory listing or a 500.
            assertEquals(404, get(client, port, "/common/does-not-exist.js").statusCode());
            assertEquals(404, get(client, port, "/common/../../build.gradle.kts").statusCode());
            assertEquals(404, get(client, port, "/unmounted/x.js").statusCode());

            // A mounted tree whose resources are absent is a 404, not a 500 (only the
            // index being missing indicates a packaging fault).
            assertEquals(404, get(client, port, "/assets/app.js").statusCode());

            // Method gating: a write to a page we DO serve is 405...
            HttpResponse<String> post = client.send(
                    HttpRequest.newBuilder(uri(port, "/")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(405, post.statusCode());

            // ...but a write to a path we serve nothing at is 404, not 405. This handler is the
            // catch-all, so it also fields POSTs to unregistered API paths (e.g. control endpoints
            // that are switched off); 405 there would imply the endpoint exists.
            HttpResponse<String> postUnknown = client.send(
                    HttpRequest.newBuilder(uri(port, "/api/session/halt"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, postUnknown.statusCode());

            HttpResponse<String> options = client.send(
                    HttpRequest.newBuilder(uri(port, "/")).method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(204, options.statusCode());
            assertTrue(options.headers().firstValue("Access-Control-Allow-Methods").orElse("").contains("GET"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingIndexIsAServerFaultNotANotFound() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.createContext("/", new StaticAssets("web/absent-index.html"));
        server.start();
        try {
            HttpResponse<String> res = get(HttpClient.newHttpClient(), server.getAddress().getPort(), "/");
            assertEquals(500, res.statusCode());
            assertFalse(res.body().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    private static URI uri(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static HttpResponse<String> get(HttpClient client, int port, String path)
            throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(uri(port, path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
