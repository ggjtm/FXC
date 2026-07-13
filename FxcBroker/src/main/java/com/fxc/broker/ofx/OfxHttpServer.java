package com.fxc.broker.ofx;

import com.fxc.common.ofx.OfxCodec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.webcohesion.ofx4j.domain.data.RequestEnvelope;
import com.webcohesion.ofx4j.domain.data.ResponseEnvelope;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Minimal HTTP transport for the OFX endpoint (docs/DESIGN.md §4.2). Uses the JDK's built-in
 * {@link HttpServer} rather than a web framework — Javalin is reserved for the deferred Mastodon
 * gateway (PROBLEMS.md B6). POSTs an OFX request, returns the OFX response; delegates all OFX logic
 * to {@link OfxService} via {@link OfxCodec}.
 */
public final class OfxHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final String path;

    public OfxHttpServer(String host, int port, String path, OfxService service) throws IOException {
        this.path = path;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.server.createContext(path, exchange -> handle(exchange, service));
        this.server.setExecutor(Executors.newFixedThreadPool(4));
    }

    public void start() {
        server.start();
    }

    public int boundPort() {
        return server.getAddress().getPort();
    }

    public String path() {
        return path;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange, OfxService service) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            RequestEnvelope request;
            try {
                request = OfxCodec.unmarshalRequest(exchange.getRequestBody());
            } catch (Exception e) {
                byte[] msg = ("OFX parse error: " + e.getMessage()).getBytes();
                exchange.sendResponseHeaders(400, msg.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(msg);
                }
                return;
            }

            ResponseEnvelope response = service.handle(request);
            byte[] body = OfxCodec.marshal(response);

            exchange.getResponseHeaders().set("Content-Type", OfxCodec.CONTENT_TYPE);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } finally {
            exchange.close();
        }
    }
}
