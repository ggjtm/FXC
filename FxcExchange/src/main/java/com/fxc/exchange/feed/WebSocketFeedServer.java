package com.fxc.exchange.feed;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * A minimal, dependency-free WebSocket server (RFC 6455) for the exchange's live feed
 * (FxcExchange/docs/stories/001). Kept hand-rolled to stay consistent with the rest of the exchange,
 * which uses no web framework (the OFX/REST layers use the JDK {@code HttpServer}; Javalin is
 * reserved for the deferred Mastodon gateway).
 *
 * <p>Scope is deliberately narrow: it performs the upgrade handshake, then pushes server→client
 * <b>text</b> frames; inbound frames are drained only to detect client close/ping. A connection's
 * {@code symbol} query parameter filters which symbols it receives ({@code *} or absent = the full
 * ticker feed of all securities). Not a general-purpose WebSocket implementation.
 */
public final class WebSocketFeedServer implements AutoCloseable {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket serverSocket;
    private final ExecutorService clients = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "fxc-ws-client");
        t.setDaemon(true);
        return t;
    });
    private final Set<Client> connections = new CopyOnWriteArraySet<>();
    private volatile boolean running = true;
    private Thread acceptThread;

    public WebSocketFeedServer(String host, int port) throws IOException {
        this.serverSocket = new ServerSocket();
        this.serverSocket.bind(new InetSocketAddress(host, port));
    }

    public int boundPort() {
        return serverSocket.getLocalPort();
    }

    public void start() {
        acceptThread = new Thread(this::acceptLoop, "fxc-ws-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /** Push a JSON message to every connected client whose symbol filter matches {@code symbol}. */
    public void publish(String symbol, String json) {
        for (Client c : connections) {
            if (c.wants(symbol)) {
                c.send(json);
            }
        }
    }

    /** Number of live connections (diagnostics / tests). */
    public int connectionCount() {
        return connections.size();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                clients.submit(() -> handshakeAndServe(socket));
            } catch (IOException e) {
                if (running) {
                    System.err.println("WS accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void handshakeAndServe(Socket socket) {
        Client client = null;
        try {
            socket.setTcpNoDelay(true);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            Map<String, String> headers = new ConcurrentHashMap<>();
            String requestLine = readHandshake(in, headers);
            String key = headers.get("sec-websocket-key");
            if (key == null) {
                socket.close();
                return;
            }
            String accept = Base64.getEncoder().encodeToString(sha1(key + GUID));
            out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();

            client = new Client(socket, out, symbolFilter(requestLine));
            connections.add(client);
            drainUntilClose(in); // blocks; returns on client close/EOF
        } catch (IOException e) {
            // client gone
        } finally {
            if (client != null) {
                connections.remove(client);
            }
            closeQuietly(socket);
        }
    }

    /** Read request line + headers (until the blank line). Returns the request line. */
    private static String readHandshake(InputStream in, Map<String, String> headers) throws IOException {
        StringBuilder line = new StringBuilder();
        String requestLine = null;
        int prev = -1;
        int b;
        while ((b = in.read()) != -1) {
            if (prev == '\r' && b == '\n') {
                String l = line.substring(0, line.length() - 1); // drop trailing \r
                if (l.isEmpty()) {
                    break; // end of headers
                }
                if (requestLine == null) {
                    requestLine = l;
                } else {
                    int colon = l.indexOf(':');
                    if (colon > 0) {
                        headers.put(l.substring(0, colon).trim().toLowerCase(),
                                l.substring(colon + 1).trim());
                    }
                }
                line.setLength(0);
            } else {
                line.append((char) b);
            }
            prev = b;
        }
        return requestLine == null ? "" : requestLine;
    }

    /** Extract the {@code symbol} query param from the GET request line ({@code null} = all). */
    private static String symbolFilter(String requestLine) {
        // "GET /ws?symbol=ACME HTTP/1.1"
        String[] parts = requestLine.split(" ");
        if (parts.length < 2 || !parts[1].contains("?")) {
            return null;
        }
        String query = parts[1].substring(parts[1].indexOf('?') + 1);
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals("symbol")) {
                String v = kv.substring(eq + 1);
                return (v.isEmpty() || v.equals("*")) ? null : v;
            }
        }
        return null;
    }

    /** Read inbound frames, discarding payloads; respond to ping, return on close/EOF. */
    private void drainUntilClose(InputStream in) throws IOException {
        while (running) {
            int b0 = in.read();
            if (b0 == -1) {
                return;
            }
            int opcode = b0 & 0x0F;
            int b1 = in.read();
            if (b1 == -1) {
                return;
            }
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = (readByte(in) << 8) | readByte(in);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | readByte(in);
                }
            }
            byte[] mask = new byte[4];
            if (masked) {
                for (int i = 0; i < 4; i++) {
                    mask[i] = (byte) readByte(in);
                }
            }
            for (long i = 0; i < len; i++) {
                readByte(in);
            }
            if (opcode == 0x8) { // close
                return;
            }
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b == -1) {
            throw new IOException("unexpected EOF");
        }
        return b;
    }

    private static byte[] sha1(String s) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    @Override
    public void close() {
        running = false;
        closeQuietly2(serverSocket);
        for (Client c : connections) {
            c.close();
        }
        connections.clear();
        clients.shutdownNow();
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
    }

    private static void closeQuietly2(ServerSocket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** One connected client: a socket plus its symbol filter. Writes are synchronized. */
    private static final class Client {
        private final Socket socket;
        private final OutputStream out;
        private final String symbol; // null = all symbols

        Client(Socket socket, OutputStream out, String symbol) {
            this.socket = socket;
            this.out = out;
            this.symbol = symbol;
        }

        boolean wants(String s) {
            return symbol == null || symbol.equals(s);
        }

        synchronized void send(String text) {
            try {
                out.write(textFrame(text));
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best effort
            }
        }

        /** Encode an unmasked server→client text frame (FIN=1, opcode=0x1). */
        private static byte[] textFrame(String text) {
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            int n = payload.length;
            byte[] header;
            if (n < 126) {
                header = new byte[] {(byte) 0x81, (byte) n};
            } else if (n <= 0xFFFF) {
                header = new byte[] {(byte) 0x81, 126, (byte) (n >> 8), (byte) n};
            } else {
                header = new byte[10];
                header[0] = (byte) 0x81;
                header[1] = 127;
                for (int i = 0; i < 8; i++) {
                    header[9 - i] = (byte) (n >>> (8 * i));
                }
            }
            byte[] frame = new byte[header.length + n];
            System.arraycopy(header, 0, frame, 0, header.length);
            System.arraycopy(payload, 0, frame, header.length, n);
            return frame;
        }
    }
}
