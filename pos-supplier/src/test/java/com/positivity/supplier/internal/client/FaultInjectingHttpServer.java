package com.positivity.supplier.internal.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Socket-level fault injection over the JDK's {@code com.sun.net.httpserver} (binding arbitration
 * decision 2). {@code MockRestServiceServer} intercepts above the transport, so it cannot produce a
 * real read timeout, a refused connection, or a genuinely slow socket — the failure modes whose
 * classification decides whether a purchase order may be safely retried.
 *
 * <p><strong>Scope discipline, per decision 2: this fixture never grows request-verification
 * features.</strong> Assertions about headers, bodies or call counts belong in
 * {@code MockRestServiceServer} tests. It exposes only a received-request count and the last
 * observed headers, which exist to prove that a request did or did not reach the wire at all — the
 * distinction between pre-send and post-send. Anything richer must be added to a protocol-level test
 * instead.
 */
final class FaultInjectingHttpServer implements AutoCloseable {

    /** How the server should misbehave. */
    enum Fault {
        /** Respond 200 with the configured body. */
        NONE,
        /** Accept the request, then never respond — drives a client read timeout. */
        HANG,
        /** Respond with the configured status code. */
        STATUS,
        /** Accept the connection then close it without any response. */
        RESET
    }

    private final HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private final AtomicReference<Fault> fault = new AtomicReference<>(Fault.NONE);
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicInteger receivedRequests = new AtomicInteger();
    private final AtomicInteger hangMillis = new AtomicInteger(5_000);
    private final Map<String, List<String>> lastHeaders = new ConcurrentHashMap<>();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();

    private FaultInjectingHttpServer(HttpServer server) {
        this.server = server;
    }

    /**
     * Starts a server on an ephemeral loopback port.
     *
     * @return the running fixture
     * @throws IOException when the socket cannot be bound
     */
    static FaultInjectingHttpServer start() throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FaultInjectingHttpServer fixture = new FaultInjectingHttpServer(httpServer);
        httpServer.createContext("/", fixture::handle);
        // Small pool: deterministic ordering, and a HANG genuinely blocks a worker.
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        fixture.executor = executor;
        httpServer.setExecutor(executor);
        httpServer.start();
        return fixture;
    }

    /** Base URL of the running server, e.g. {@code http://127.0.0.1:54321}. */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * A port that nothing is listening on — for connection-refused tests. Derived by starting and
     * immediately stopping a server, so the port is real but closed.
     *
     * @return a base URL that will refuse connections
     * @throws IOException when a port cannot be allocated
     */
    static String refusedBaseUrl() throws IOException {
        // Deliberately a plain ServerSocket, NOT HttpServer.create + stop: create() binds the
        // socket, and stopping a server that was never started can leave the port bound without an
        // accept loop, so a client connects into the backlog and then READ-times-out. That would
        // silently turn a pre-send test into a post-send one and quietly invert what it proves.
        // A closed ServerSocket gives a genuine ECONNREFUSED.
        int port;
        try (java.net.ServerSocket socket =
                new java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        return "http://127.0.0.1:" + port;
    }

    FaultInjectingHttpServer respondOk(String responseBody) {
        fault.set(Fault.NONE);
        body.set(responseBody);
        return this;
    }

    FaultInjectingHttpServer respondStatus(int statusCode, String responseBody) {
        fault.set(Fault.STATUS);
        status.set(statusCode);
        body.set(responseBody);
        return this;
    }

    /** Accept the request and never answer, so the client hits its read timeout. */
    FaultInjectingHttpServer hang() {
        fault.set(Fault.HANG);
        return this;
    }

    /** Accept the connection then drop it with no response. */
    FaultInjectingHttpServer reset() {
        fault.set(Fault.RESET);
        return this;
    }

    /** How many requests actually reached the wire — the pre-send / post-send discriminator. */
    int receivedRequests() {
        return receivedRequests.get();
    }

    /**
     * Headers of the most recent request, keyed lower-case. The JDK server rewrites header names to
     * its own capitalization ({@code X-correlation-id}), so lookups must be case-insensitive to
     * assert on what was actually sent. Used only to prove a header reached the wire.
     */
    Map<String, List<String>> lastHeaders() {
        Map<String, List<String>> normalized = new java.util.LinkedHashMap<>();
        lastHeaders.forEach((name, values) -> normalized.put(name.toLowerCase(java.util.Locale.ROOT), values));
        return Map.copyOf(normalized);
    }

    /**
     * Values of one request header, matched case-insensitively.
     *
     * @param name header name in any casing
     * @return the values, or an empty list when the header was not sent
     */
    List<String> header(String name) {
        List<String> values = lastHeaders().get(name.toLowerCase(java.util.Locale.ROOT));
        return values == null ? List.of() : values;
    }

    /** Bodies received, in order. */
    List<String> requestBodies() {
        return List.copyOf(requestBodies);
    }

    private void handle(HttpExchange exchange) throws IOException {
        receivedRequests.incrementAndGet();
        lastHeaders.clear();
        exchange.getRequestHeaders().forEach(lastHeaders::put);
        requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        Fault current = fault.get();
        try {
            switch (current) {
                case HANG -> {
                    // Block without responding: the client must hit its read timeout.
                    try {
                        Thread.sleep(hangMillis.get());
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
                case RESET -> exchange.close();
                case STATUS -> respond(exchange, status.get(), body.get());
                case NONE -> respond(exchange, 200, body.get());
            }
        } finally {
            if (current != Fault.RESET) {
                exchange.close();
            }
        }
    }

    private static void respond(HttpExchange exchange, int statusCode, String payload) throws IOException {
        byte[] bytes = payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        if (executor != null) {
            // Without this, every test leaked 4 threads and a HANG left one sleeping for up to 60s,
            // so a full-suite run accumulated blocked threads for no reason.
            executor.shutdownNow();
        }
    }
}
