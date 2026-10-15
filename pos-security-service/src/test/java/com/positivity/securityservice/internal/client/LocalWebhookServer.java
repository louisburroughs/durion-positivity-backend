package com.positivity.securityservice.internal.client;

import com.positivity.securityservice.internal.config.AuditExportWebhookProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * A local HTTPS webhook receiver for the delivery tests (#2497): {@link HttpsServer} on the loopback
 * interface with a self-signed certificate for {@code localhost} (made once per JVM with the JDK's
 * {@code keytool}), answering each request with the next scripted reply and recording what it got.
 *
 * <p>Its {@link #sender} methods build the real {@link AuditExportWebhookSender} through the
 * package-private seams: a client that trusts the certificate, and a destination policy that admits
 * loopback addresses (tests only; production admits none) and may resolve names differently.
 */
public final class LocalWebhookServer implements AutoCloseable {

    private static final char[] PASSWORD = "test-only".toCharArray();
    private static volatile KeyStore keyStore;

    private final HttpsServer server;
    private final SSLContext clientContext;
    private final Deque<Reply> replies = new ConcurrentLinkedDeque<>();
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean closed = new AtomicBoolean();

    private LocalWebhookServer(HttpsServer server, SSLContext clientContext) {
        this.server = server;
        this.clientContext = clientContext;
    }

    /** Starts a receiver on an ephemeral loopback port; every request answers 200 until scripted otherwise. */
    public static LocalWebhookServer start() throws Exception {
        KeyStore store = keyStore();
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASSWORD);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keys.getKeyManagers(), null, null);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, trust.getTrustManagers(), null);

        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        LocalWebhookServer webhook = new LocalWebhookServer(server, clientContext);
        server.createContext("/", webhook::handle);
        server.start();
        return webhook;
    }

    private static KeyStore keyStore() throws Exception {
        KeyStore existing = keyStore;
        if (existing != null) {
            return existing;
        }
        synchronized (LocalWebhookServer.class) {
            if (keyStore == null) {
                Path dir = Files.createTempDirectory("audit-webhook-tls");
                Path file = dir.resolve("localhost.p12");
                Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
                Process process = new ProcessBuilder(
                                keytool.toString(),
                                "-genkeypair",
                                "-alias",
                                "localhost",
                                "-keyalg",
                                "EC",
                                "-groupname",
                                "secp256r1",
                                "-validity",
                                "2",
                                "-dname",
                                "CN=localhost",
                                "-ext",
                                "SAN=dns:localhost,ip:127.0.0.1",
                                "-keystore",
                                file.toString(),
                                "-storetype",
                                "PKCS12",
                                "-storepass",
                                new String(PASSWORD),
                                "-keypass",
                                new String(PASSWORD))
                        .redirectErrorStream(true)
                        .start();
                String output = new String(process.getInputStream().readAllBytes());
                if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                    throw new IllegalStateException("keytool failed: " + output);
                }
                KeyStore store = KeyStore.getInstance("PKCS12");
                try (InputStream in = Files.newInputStream(file)) {
                    store.load(in, PASSWORD);
                }
                keyStore = store;
            }
            return keyStore;
        }
    }

    /** {@code https://localhost:<port><path>}: a DNS name, as a registered destination must be. */
    public URI url(String path) {
        return URI.create("https://localhost:" + server.getAddress().getPort() + path);
    }

    /** Scripts the next replies, in order: an HTTP status each. */
    public LocalWebhookServer replyWith(int... statuses) {
        for (int status : statuses) {
            replies.add(new Reply(status, null));
        }
        return this;
    }

    /** Scripts the next reply as a redirect to {@code location}. */
    public LocalWebhookServer redirectTo(int status, String location) {
        replies.add(new Reply(status, location));
        return this;
    }

    /** What the receiver got, in order. */
    public List<Received> received() {
        synchronized (received) {
            return List.copyOf(received);
        }
    }

    /** The real sender, trusting this server, admitting loopback and resolving names through DNS. */
    public AuditExportWebhookSender sender(AuditExportWebhookProperties properties, Clock clock) {
        return new AuditExportWebhookSender(
                new WebhookDestinationPolicy(InetAddress::getAllByName, InetAddress::isLoopbackAddress),
                properties,
                clock,
                clientContext);
    }

    /**
     * The real sender, trusting this server, whose destination check resolves every host to {@code
     * resolvedTo} instead (the connection itself still resolves through DNS): a destination that
     * re-resolves to a refused address at delivery time.
     */
    public AuditExportWebhookSender senderResolvingTo(
            AuditExportWebhookProperties properties, Clock clock, InetAddress... resolvedTo) {
        return new AuditExportWebhookSender(
                new WebhookDestinationPolicy(host -> resolvedTo.clone(), InetAddress::isLoopbackAddress),
                properties,
                clock,
                clientContext);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, String.join(",", values)));
        received.add(new Received(
                exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, body));
        Reply reply = replies.poll();
        int status = reply == null ? 200 : reply.status();
        if (reply != null && reply.location() != null) {
            exchange.getResponseHeaders().add("Location", reply.location());
        }
        byte[] answer = "receiver body that must never be recorded".getBytes();
        exchange.sendResponseHeaders(status, answer.length);
        exchange.getResponseBody().write(answer);
        exchange.close();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            server.stop(0);
        }
    }

    private record Reply(int status, String location) {}

    /** One request the receiver got. */
    public record Received(String method, String path, Map<String, String> headers, byte[] body) {

        public String header(String name) {
            return headers.get(name);
        }
    }
}
