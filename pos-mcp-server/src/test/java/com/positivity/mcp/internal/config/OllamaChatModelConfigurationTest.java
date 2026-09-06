package com.positivity.mcp.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.RestClientException;

/**
 * The chat backend can require authentication (e.g. ollama.com). {@code OllamaApi} talks to it over
 * a RestClient for blocking calls and a separate WebClient for streaming, so the API key must reach
 * both clients — a header on only one of them makes the other path 401 against the live backend.
 * These tests drive each path against a local HTTP server and assert the relayed header.
 *
 * <p>They also assert the options actually put on the wire (#1683): {@code num_ctx} must always be
 * sent — inheriting the Ollama host's {@code OLLAMA_CONTEXT_LENGTH} silently truncates the front of
 * the context, i.e. the system prompt — and {@code temperature} must be whatever was configured,
 * with 0 the deterministic default for the graded analytics workload.
 *
 * <p>And they pin the retry budget (#1749): an upstream 500 is retried exactly twice and then
 * surfaced, a 4xx and a read timeout are not retried, and streaming never retries — the default
 * template's ten retries with back-off to three minutes is how one 500 became a 180 s hang.
 */
class OllamaChatModelConfigurationTest {

    private static final String NO_HEADER = "<no-authorization-header>";

    private static final String CHAT_RESPONSE = """
            {"model":"test-model","created_at":"2026-01-01T00:00:00Z",\
            "message":{"role":"assistant","content":"ok"},"done":true,"done_reason":"stop"}
            """;

    private final OllamaChatModelConfiguration configuration = new OllamaChatModelConfiguration();
    private final ConcurrentLinkedQueue<String> authorizationHeaders = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> requestBodies = new ConcurrentLinkedQueue<>();
    /** Built through the bean method, so the test exercises what production wires. */
    private static final RetryTemplate RETRY = new OllamaChatModelConfiguration()
            .ollamaChatRetryTemplate(2, Duration.ofMillis(20), 2, Duration.ofMillis(100));

    private volatile int failWithStatus = 0;
    private volatile long delayMillis = 0;
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            authorizationHeaders.add(header == null ? NO_HEADER : header);
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(request);
            boolean streaming = request.contains("\"stream\":true");
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failWithStatus != 0) {
                byte[] error = "{\"error\":\"Internal Server Error (ref: test)\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(failWithStatus, error.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(error);
                }
                return;
            }
            byte[] body = CHAT_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", streaming ? "application/x-ndjson" : "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void streamingChatModel_relaysApiKeyAsBearerHeader() {
        StreamingChatModel model = configuration.streamingChatModel(
                baseUrl, "test-model", "secret-key", Duration.ofSeconds(10), "", 0.0d, 32768, RETRY);

        model.stream(new Prompt("hi")).blockLast(Duration.ofSeconds(10));

        assertThat(authorizationHeaders).containsExactly("Bearer secret-key");
    }

    @Test
    void chatModel_relaysApiKeyAsBearerHeader() {
        ChatModel model = configuration.chatModel(
                baseUrl, "test-model", "secret-key", Duration.ofSeconds(10), "", 0.0d, 32768, RETRY);

        model.call(new Prompt("hi"));

        assertThat(authorizationHeaders).containsExactly("Bearer secret-key");
    }

    @Test
    void blankApiKey_sendsNoAuthorizationHeader() {
        StreamingChatModel model = configuration.streamingChatModel(
                baseUrl, "test-model", "", Duration.ofSeconds(10), "", 0.0d, 32768, RETRY);

        model.stream(new Prompt("hi")).blockLast(Duration.ofSeconds(10));

        assertThat(authorizationHeaders).containsExactly(NO_HEADER);
    }

    @Test
    void chatModel_sendsConfiguredNumCtxAndTemperature() {
        ChatModel model =
                configuration.chatModel(baseUrl, "test-model", "", Duration.ofSeconds(10), "", 0.0d, 32768, RETRY);

        model.call(new Prompt("hi"));

        assertSentOptions(32768, 0.0d);
    }

    @Test
    void streamingChatModel_sendsConfiguredNumCtxAndTemperature() {
        StreamingChatModel model = configuration.streamingChatModel(
                baseUrl, "test-model", "", Duration.ofSeconds(10), "", 0.0d, 32768, RETRY);

        model.stream(new Prompt("hi")).blockLast(Duration.ofSeconds(10));

        assertSentOptions(32768, 0.0d);
    }

    /**
     * num_ctx is never omitted, whatever the value: an unset option is what hands the context
     * window back to the host default this change exists to stop relying on.
     */
    @Test
    void nonDefaultNumCtxAndTemperature_areSentAsConfigured() {
        ChatModel model =
                configuration.chatModel(baseUrl, "test-model", "", Duration.ofSeconds(10), "", 0.7d, 8192, RETRY);

        model.call(new Prompt("hi"));

        assertSentOptions(8192, 0.7d);
    }

    /**
     * Parses the captured request body and asserts the numeric option values, rather than matching
     * raw JSON substrings. A substring assertion on {@code "temperature":0.7} holds only because
     * Jackson happens to render that double unpadded — a serializer change or a field-type change
     * would break the test while the behaviour stayed correct.
     */
    private void assertSentOptions(int expectedNumCtx, double expectedTemperature) {
        assertThat(requestBodies).singleElement().satisfies(body -> {
            JsonNode options = new ObjectMapper().readTree(body).path("options");
            assertThat(options.path("num_ctx").isMissingNode())
                    .as("num_ctx must always be sent (#1683), never left to the backend default")
                    .isFalse();
            assertThat(options.path("num_ctx").intValue()).isEqualTo(expectedNumCtx);
            assertThat(options.path("temperature").doubleValue()).isEqualTo(expectedTemperature);
        });
    }

    @Test
    void upstream500_isRetriedWithinTheBoundedBudgetThenSurfaced() {
        // #1749: ollama.com answered 500 on a gate question and Spring AI's default template kept
        // the turn alive through back-offs of 8 s, 11 s, 50 s and 181 s while the client timed out
        // at 180 s. With the bounded template the turn makes its three requests and fails fast.
        failWithStatus = 500;
        ChatModel model = configuration.chatModel(
                baseUrl, "test-model", "secret-key", Duration.ofSeconds(10), "", 0.0d, 32768, RETRY);

        assertTimeoutPreemptively(
                Duration.ofSeconds(15),
                () -> assertThatThrownBy(() -> model.call(new Prompt("hi")))
                        .isInstanceOf(TransientAiException.class)
                        .hasMessageContaining("500"));

        assertThat(requestBodies).hasSize(3);
    }

    @Test
    void upstream4xx_isNotRetried() {
        // A client error is the caller's fault and will not change on retry; one request, surfaced.
        failWithStatus = 400;
        ChatModel model = configuration.chatModel(
                baseUrl, "test-model", "secret-key", Duration.ofSeconds(10), "", 0.0d, 32768, RETRY);

        assertThatThrownBy(() -> model.call(new Prompt("hi"))).isInstanceOf(NonTransientAiException.class);

        assertThat(requestBodies).hasSize(1);
    }

    @Test
    void readTimeout_isNotRetried() {
        // Each attempt would cost the full chat timeout; three of those is not an interactive turn.
        // The timeout fires while the body is being read, so RestClient reports it as an extraction
        // failure with the SocketTimeoutException underneath; either way it must not be retried.
        delayMillis = 1500;
        ChatModel model = configuration.chatModel(
                baseUrl, "test-model", "secret-key", Duration.ofMillis(300), "", 0.0d, 32768, RETRY);

        assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                () -> assertThatThrownBy(() -> model.call(new Prompt("hi")))
                        .isInstanceOf(RestClientException.class)
                        .hasRootCauseInstanceOf(SocketTimeoutException.class));

        assertThat(requestBodies).hasSize(1);
    }

    @Test
    void streaming500_isNotRetriedAtAll() {
        // Documents Spring AI 2.0 behaviour rather than choosing it: the streaming path never
        // consults the retry template, so a streamed 500 surfaces after a single request.
        failWithStatus = 500;
        StreamingChatModel model = configuration.streamingChatModel(
                baseUrl, "test-model", "secret-key", Duration.ofSeconds(10), "", 0.0d, 32768, RETRY);

        assertThatThrownBy(() -> model.stream(new Prompt("hi")).blockLast(Duration.ofSeconds(10)))
                .isInstanceOf(Exception.class);

        assertThat(requestBodies).hasSize(1);
    }
}
