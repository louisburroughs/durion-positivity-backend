package com.positivity.mcp.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.config.TaggingProperties;
import com.positivity.mcp.internal.domain.FallbackReason;
import com.positivity.mcp.internal.domain.TagName;
import com.positivity.mcp.internal.domain.TagQuestion;
import com.positivity.mcp.internal.domain.TaggingMode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * ADR-0068 §4, §5 / spec §2.2, §4: the System One client against a stubbed server. The whole failure
 * matrix, the request-body minimisation, and the promise that no log line ever carries the state.
 */
class JevClientTest {

    /** A message that must never appear in a request field other than {@code state} or in any log line. */
    private static final String STATE = "who has access to the receivables ledger for customer ACME-7731?";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The trickled body: this many bytes, one every {@link #TRICKLE_INTERVAL_MS}. */
    private static final int TRICKLE_BYTES = 40;

    private static final long TRICKLE_INTERVAL_MS = 100;

    /** How long the trickled body takes to end if nothing cuts it off (~4 s). */
    private static final long TRICKLE_MS = TRICKLE_BYTES * TRICKLE_INTERVAL_MS;

    /** How long the slow-headers stub withholds its headers. */
    private static final long SLOW_HEADERS_MS = 4000;

    /**
     * Scheduling slack on a timing assertion: wide enough for a loaded CI runner, still well under
     * {@link #TRICKLE_MS} and {@link #SLOW_HEADERS_MS}, so a call that waits for the stub fails it.
     */
    private static final long TIMING_SLACK_MS = 1500;

    private HttpServer server;
    private ExecutorService serverExecutor;
    private Level originalLevel;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<Map<String, List<String>>> lastHeaders = new AtomicReference<>();
    private final AtomicReference<Responder> responder = new AtomicReference<>();
    /** Counted down once the trickled body's first byte is on the wire: the call is in flight. */
    private final CountDownLatch bodyStarted = new CountDownLatch(1);
    /** Counted down when a trickled write fails: the client closed the connection. */
    private final CountDownLatch clientGone = new CountDownLatch(1);

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger logger;

    private interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastHeaders.set(exchange.getRequestHeaders());
            responder.get().respond(exchange);
        });
        // Handlers run off the dispatcher thread, so a stalled handler neither blocks a second
        // exchange nor holds up stop(); the executor's shutdownNow() interrupts any still sleeping.
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();
        logs.start();
        logger = (Logger) LoggerFactory.getLogger(JevClient.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(logs);
    }

    @AfterEach
    void stop() {
        serverExecutor.shutdownNow();
        server.stop(0);
        logger.detachAppender(logs);
        logger.setLevel(originalLevel);
        logs.stop();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private TaggingProperties properties(Duration timeout, String apiKey, String keepAlive, int maxStateChars) {
        return new TaggingProperties(
                TaggingMode.SHADOW,
                List.of(),
                new TaggingProperties.Provider(baseUrl(), "tev1:0.8b", timeout, apiKey, keepAlive),
                Map.of(),
                maxStateChars);
    }

    private JevClient client() {
        return new JevClient(properties(Duration.ofSeconds(2), null, "30m", 4000));
    }

    private static List<TagQuestion> questions() {
        SequencedMap<String, String> workflow = new LinkedHashMap<>();
        workflow.put("IDLE", "nothing");
        workflow.put("CREATING_PO", "a purchase order");
        return List.of(
                TagQuestion.noul(TagName.SIMPLE_CHAT, "Is it small talk?"),
                TagQuestion.choice(TagName.WORKFLOW_STATE, "Which workflow?", workflow),
                TagQuestion.score(TagName.RISK, "How risky?", List.of("LOW", "MEDIUM", "HIGH")));
    }

    private static final String HAPPY_BODY = """
            { "model": "tev1:0.8b",
              "answers": {
                "simple_chat":    { "type": "noul",   "noul": 0.93 },
                "workflow_state": { "type": "choice", "choice": "IDLE",
                                    "probabilities": { "IDLE": 0.88, "CREATING_PO": 0.12 }, "confidence": 0.81 },
                "risk":           { "type": "score",  "score": 0.41, "legend": { "0": "LOW", "1": "MEDIUM", "2": "HIGH" },
                                    "probabilities": { "0": 0.55, "1": 0.30, "2": 0.15 }, "confidence": 0.35 } },
              "usage": { "input_tokens": 120, "output_tokens": 3 } }
            """;

    /**
     * Headers at once, then the first {@link #TRICKLE_BYTES} bytes of the happy body one every {@link
     * #TRICKLE_INTERVAL_MS}: each read is well inside any budget, the body as a whole is not. Counts
     * {@link #bodyStarted} down at the first byte and {@link #clientGone} down when a write fails.
     */
    private void trickleBody() {
        responder.set(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            byte[] body = HAPPY_BODY.getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int index = 0; index < TRICKLE_BYTES; index++) {
                    out.write(body[index]);
                    out.flush();
                    bodyStarted.countDown();
                    Thread.sleep(TRICKLE_INTERVAL_MS);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException closedByClient) {
                clientGone.countDown();
            }
        });
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private void respondWith(int status, String body) {
        responder.set(exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    @Test
    @DisplayName("happy path: all three primitives parsed, the score's argmax level and weighted score kept")
    void happyPath() throws Exception {
        respondWith(200, HAPPY_BODY);

        JevClient.JevResponse response = client().ask(STATE, questions());

        assertThat(response.model()).isEqualTo("tev1:0.8b");
        assertThat(response.stateTruncated()).isFalse();
        assertThat(response.answers()).containsOnlyKeys("simple_chat", "workflow_state", "risk");
        assertThat(response.answers().get("simple_chat")).isEqualTo(new JevAnswer.Noul(0.93));
        JevAnswer.Choice workflow = (JevAnswer.Choice) response.answers().get("workflow_state");
        assertThat(workflow.label()).isEqualTo("IDLE");
        assertThat(workflow.confidence()).isEqualTo(0.81);
        assertThat(workflow.probabilities()).containsEntry("CREATING_PO", 0.12);
        JevAnswer.Score risk = (JevAnswer.Score) response.answers().get("risk");
        assertThat(risk.level()).isEqualTo("LOW");
        assertThat(risk.confidence()).isEqualTo(0.35);
        assertThat(risk.score()).isEqualTo(0.41);
        assertThat(risk.probabilities()).containsEntry("HIGH", 0.15);

        // Spec §2.2 / ADR-0068 §4: exactly model, state, keep_alive and questions; every question
        // carries instructions; nothing about the caller.
        JsonNode body = MAPPER.readTree(lastBody.get());
        assertThat(body.fieldNames()).toIterable().containsExactly("model", "state", "keep_alive", "questions");
        assertThat(body.get("model").textValue()).isEqualTo("tev1:0.8b");
        assertThat(body.get("state").textValue()).isEqualTo(STATE);
        assertThat(body.get("keep_alive").textValue()).isEqualTo("30m");
        assertThat(body.get("questions").fieldNames())
                .toIterable()
                .containsExactly("simple_chat", "workflow_state", "risk");
        body.get("questions").forEach(question -> {
            assertThat(question.get("instructions").textValue()).isNotBlank();
            assertThat(question.get("type").textValue()).isIn("noul", "choice", "score");
        });
        assertThat(body.get("questions").get("workflow_state").get("criteria").fieldNames())
                .toIterable()
                .containsExactly("IDLE", "CREATING_PO");
        assertThat(body.get("questions").get("risk").get("criteria").isArray()).isTrue();
        assertThat(body.get("questions").get("simple_chat").has("criteria")).isFalse();
        assertThat(lastHeaders.get().containsKey("Authorization")).isFalse();
        // The state appears in the body under "state" only.
        assertThat(lastBody.get().indexOf(STATE)).isEqualTo(lastBody.get().lastIndexOf(STATE));
        assertNoLogCarriesTheState();
    }

    @Test
    @DisplayName("score ties go to the higher level; a missing legend falls back to the question's level order")
    void scoreTiesGoToTheHigherLevelWithoutALegend() {
        respondWith(200, """
                { "answers": {
                    "simple_chat": { "type": "noul", "noul": 0.2 },
                    "workflow_state": { "type": "choice", "choice": "CREATING_PO", "confidence": 0.6 },
                    "risk": { "type": "score", "probabilities": { "0": 0.4, "1": 0.4, "2": 0.2 }, "confidence": 0.5 } } }
                """);

        JevClient.JevResponse response = client().ask(STATE, questions());

        assertThat(((JevAnswer.Score) response.answers().get("risk")).level()).isEqualTo("MEDIUM");
        assertThat(((JevAnswer.Score) response.answers().get("risk")).score()).isNull();
        // The provider named no model: the configured one is reported.
        assertThat(response.model()).isEqualTo("tev1:0.8b");
    }

    @Test
    @DisplayName("the api key is sent as a bearer token and never logged")
    void apiKeyIsABearerHeader() throws Exception {
        respondWith(200, HAPPY_BODY);
        JevClient client = new JevClient(properties(Duration.ofSeconds(2), "sk-secret-123", "", 4000));

        client.ask(STATE, questions());

        assertThat(lastHeaders.get().get("Authorization")).containsExactly("Bearer sk-secret-123");
        assertThat(MAPPER.readTree(lastBody.get()).has("keep_alive"))
                .as("blank keep-alive is omitted")
                .isFalse();
        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("sk-secret-123"));
    }

    @Test
    @DisplayName("the state is cut at max-state-chars and the cut is reported")
    void stateIsCutAtMaxStateChars() throws Exception {
        respondWith(200, HAPPY_BODY);
        JevClient client = new JevClient(properties(Duration.ofSeconds(2), null, "30m", 10));

        JevClient.JevResponse response = client.ask(STATE, questions());

        assertThat(response.stateTruncated()).isTrue();
        assertThat(MAPPER.readTree(lastBody.get()).get("state").textValue()).isEqualTo(STATE.substring(0, 10));
    }

    @Test
    @DisplayName("timeout → TIMEOUT, the turn takes the heuristic answers")
    void timeout() {
        responder.set(exchange -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        JevClient client = new JevClient(properties(Duration.ofMillis(200), null, "30m", 4000));

        assertFailure(() -> client.ask(STATE, questions()), FallbackReason.TIMEOUT, null);
    }

    /**
     * PR #2367 review: the budget is ONE deadline for the whole call. A provider that sends its
     * headers at once and then trickles the body, a byte well inside any per-read timeout each time,
     * must still be cut off at the budget, not after the body ends.
     */
    @Test
    @DisplayName("headers at once, then a body trickled past the budget → TIMEOUT within the budget")
    void trickledBodyIsCutAtTheOverallDeadline() {
        Duration budget = Duration.ofMillis(400);
        trickleBody();
        JevClient client = new JevClient(properties(budget, null, "30m", 4000));

        long startNanos = System.nanoTime();
        assertFailure(() -> client.ask(STATE, questions()), FallbackReason.TIMEOUT, null);
        long elapsedMs = elapsedMs(startNanos);

        // The upper bound carries CI slack and must still sit well under the trickle, or it would
        // not tell a call cut at the budget from one that waited for the body to end.
        assertThat(budget.toMillis() + TIMING_SLACK_MS).isLessThan(TRICKLE_MS);
        assertThat(elapsedMs)
                .as("the call ends at the budget, not when the trickled body ends (~4 s)")
                .isGreaterThanOrEqualTo(budget.toMillis() - 50)
                .isLessThan(budget.toMillis() + TIMING_SLACK_MS);
    }

    /**
     * PR #2367 review: on expiry the exchange is cancelled, not abandoned. Without the cancel the
     * client's own I/O keeps reading the trickled body in the background until it ends (~4 s).
     */
    @Test
    @DisplayName(
            "timeout cancels the pending exchange: the provider sees the connection closed well before the body ends")
    void timeoutCancelsThePendingExchange() throws InterruptedException {
        Duration budget = Duration.ofMillis(400);
        trickleBody();
        JevClient client = new JevClient(properties(budget, null, "30m", 4000));

        long startNanos = System.nanoTime();
        assertFailure(() -> client.ask(STATE, questions()), FallbackReason.TIMEOUT, null);

        assertThat(clientGone.await(TIMING_SLACK_MS, TimeUnit.MILLISECONDS))
                .as("the stub's next write fails once the client has dropped the exchange")
                .isTrue();
        assertThat(elapsedMs(startNanos))
                .as("the connection closes at the budget, not when the trickled body ends")
                .isLessThan(budget.toMillis() + TIMING_SLACK_MS)
                .isLessThan(TRICKLE_MS);
    }

    /** A call in flight when the context closes ends at once as a typed ERROR fallback, never a hang. */
    @Test
    @DisplayName("destroy() during a call in flight → ERROR at once, the exchange aborted")
    void destroyEndsACallInFlightAsAnError() throws Exception {
        trickleBody();
        // A budget far past the trickle: only destroy() can end this call early.
        JevClient client = new JevClient(properties(Duration.ofSeconds(30), null, "30m", 4000));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<JevClient.JevResponse> call = caller.submit(() -> client.ask(STATE, questions()));
            assertThat(bodyStarted.await(5, TimeUnit.SECONDS)).isTrue();

            long destroyNanos = System.nanoTime();
            client.destroy();

            assertThatThrownBy(() -> call.get(TIMING_SLACK_MS, TimeUnit.MILLISECONDS))
                    .as("the call ends when the client shuts down, not when the trickled body ends")
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOfSatisfying(
                            JevProviderException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(FallbackReason.ERROR));
            assertThat(elapsedMs(destroyNanos)).isLessThan(TRICKLE_MS);
            assertThat(clientGone.await(TIMING_SLACK_MS, TimeUnit.MILLISECONDS))
                    .as("the shutdown aborts the exchange")
                    .isTrue();
            assertNoLogCarriesTheState();
        } finally {
            caller.shutdownNow();
        }
    }

    /**
     * Interrupting the caller mid-call ends it as a typed ERROR, cancels the exchange, and leaves the
     * thread's interrupt flag set for the caller's own code to see.
     */
    @Test
    @DisplayName("an interrupted caller → ERROR, the exchange cancelled, the interrupt flag kept")
    void interruptedCallerGetsAnErrorAndKeepsItsInterruptFlag() throws InterruptedException {
        trickleBody();
        JevClient client = new JevClient(properties(Duration.ofSeconds(30), null, "30m", 4000));
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptedAfterwards = new AtomicBoolean();
        Thread caller = new Thread(
                () -> {
                    try {
                        client.ask(STATE, questions());
                    } catch (RuntimeException failure) {
                        thrown.set(failure);
                    }
                    interruptedAfterwards.set(Thread.currentThread().isInterrupted());
                },
                "jev-client-interrupt-test");
        caller.start();
        assertThat(bodyStarted.await(5, TimeUnit.SECONDS)).isTrue();

        caller.interrupt();
        caller.join(TIMING_SLACK_MS);

        assertThat(caller.isAlive())
                .as("the call ends on the interrupt, not when the trickled body ends")
                .isFalse();
        assertThat(thrown.get())
                .isInstanceOfSatisfying(
                        JevProviderException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(FallbackReason.ERROR));
        assertThat(interruptedAfterwards.get())
                .as("the interrupt flag is restored for the caller")
                .isTrue();
        assertThat(clientGone.await(TIMING_SLACK_MS, TimeUnit.MILLISECONDS))
                .as("the interrupted call cancels its exchange")
                .isTrue();
        assertNoLogCarriesTheState();
    }

    /**
     * A timeout beyond what a nanosecond deadline holds saturates at {@code Provider.MAX_TIMEOUT}: an
     * absurd setting behaves as a very long budget, not as an overflow that fails every turn.
     */
    @Test
    @DisplayName("an absurd timeout saturates to a very long budget: the call still succeeds")
    void absurdTimeoutSaturatesInsteadOfOverflowing() {
        respondWith(200, HAPPY_BODY);
        TaggingProperties absurd = properties(Duration.ofSeconds(Long.MAX_VALUE), null, "30m", 4000);

        assertThat(absurd.provider().timeout()).isEqualTo(TaggingProperties.Provider.MAX_TIMEOUT);
        assertThat(properties(Duration.ofDays(365L * 1000), null, "30m", 4000)
                        .provider()
                        .timeout())
                .isEqualTo(TaggingProperties.Provider.MAX_TIMEOUT);
        assertThat(properties(Duration.ofDays(1), null, "30m", 4000).provider().timeout())
                .as("a long but representable timeout is kept as set")
                .isEqualTo(Duration.ofDays(1));

        JevClient.JevResponse response = new JevClient(absurd).ask(STATE, questions());

        assertThat(response.answers()).containsOnlyKeys("simple_chat", "workflow_state", "risk");
    }

    @Test
    @DisplayName("headers withheld past the budget → TIMEOUT within the budget")
    void slowHeadersAreCutAtTheOverallDeadline() {
        Duration budget = Duration.ofMillis(300);
        responder.set(exchange -> {
            try {
                Thread.sleep(SLOW_HEADERS_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        JevClient client = new JevClient(properties(budget, null, "30m", 4000));

        long startNanos = System.nanoTime();
        assertFailure(() -> client.ask(STATE, questions()), FallbackReason.TIMEOUT, null);

        assertThat(budget.toMillis() + TIMING_SLACK_MS).isLessThan(SLOW_HEADERS_MS);
        assertThat(elapsedMs(startNanos))
                .as("the call ends at the budget, not when the withheld headers arrive (~4 s)")
                .isLessThan(budget.toMillis() + TIMING_SLACK_MS);
    }

    @Test
    @DisplayName("ADR-0068 §3.6: the provider's reported model string is never used, logged or returned")
    void providerReportedModelIsIgnored() {
        respondWith(200, HAPPY_BODY.replace("\"model\": \"tev1:0.8b\"", "\"model\": \"rogue-model {} injected\""));

        JevClient.JevResponse response = client().ask(STATE, questions());

        assertThat(response.model()).isEqualTo("tev1:0.8b");
        assertThat(logs.list).isNotEmpty();
        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("rogue-model"));
        assertThat(logs.list).anyMatch(event -> event.getFormattedMessage().contains("model=tev1:0.8b"));
    }

    @Test
    @DisplayName("connection refused → ERROR")
    void connectionRefused() {
        server.stop(0);
        int port = server.getAddress().getPort();
        TaggingProperties properties = new TaggingProperties(
                TaggingMode.SHADOW,
                List.of(),
                new TaggingProperties.Provider(
                        "http://127.0.0.1:" + port, "tev1:0.8b", Duration.ofMillis(500), null, null),
                Map.of(),
                4000);

        assertFailure(() -> new JevClient(properties).ask(STATE, questions()), FallbackReason.ERROR, null);
    }

    @ParameterizedTest(name = "HTTP {0} → RATE_LIMITED")
    @ValueSource(ints = {429, 529})
    void rateLimited(int status) {
        respondWith(status, "{\"error\":\"overloaded\"}");

        assertFailure(() -> client().ask(STATE, questions()), FallbackReason.RATE_LIMITED, status);
    }

    @ParameterizedTest(name = "HTTP {0} with an empty body → RATE_LIMITED")
    @ValueSource(ints = {429, 529})
    void rateLimitedWithAnEmptyBody(int status) {
        responder.set(exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });

        assertFailure(() -> client().ask(STATE, questions()), FallbackReason.RATE_LIMITED, status);
    }

    @ParameterizedTest(name = "HTTP {0} with a zero-length chunked body → RATE_LIMITED")
    @ValueSource(ints = {429, 529})
    void rateLimitedWithAZeroLengthChunkedBody(int status) {
        responder.set(exchange -> {
            exchange.sendResponseHeaders(status, 0);
            exchange.getResponseBody().close();
        });

        assertFailure(() -> client().ask(STATE, questions()), FallbackReason.RATE_LIMITED, status);
    }

    @ParameterizedTest(name = "HTTP {0} → ERROR")
    @ValueSource(ints = {400, 404, 500, 503})
    void otherStatuses(int status) {
        respondWith(status, "{\"error\":\"model not found\"}");

        assertFailure(() -> client().ask(STATE, questions()), FallbackReason.ERROR, status);
    }

    @Test
    @DisplayName("an Ollama {\"error\": ...} body with a 2xx → ERROR, with the error text's length only in the log")
    void errorBodyWithSuccessStatus() {
        respondWith(200, "{\"error\":\"model 'tev1:0.8b' not found; echo: " + STATE + "\"}");

        assertFailure(() -> client().ask(STATE, questions()), FallbackReason.ERROR, 200);
        assertThat(logs.list)
                .anyMatch(event -> event.getFormattedMessage().contains("errorTextLength="))
                .noneMatch(event -> event.getFormattedMessage().contains("not found"));
    }

    @Test
    @DisplayName("malformed JSON → MALFORMED")
    void malformedJson() {
        respondWith(200, "{ this is not json");

        assertFailure(() -> client().ask(STATE, questions()), FallbackReason.MALFORMED, 200);
    }

    @Test
    @DisplayName("a JSON body that is not an object, or has no answers → MALFORMED")
    void notAnObjectOrNoAnswers() {
        respondWith(200, "[1,2,3]");
        assertFailure(() -> client().ask(STATE, questions()), FallbackReason.MALFORMED, 200);

        respondWith(200, "{\"model\":\"tev1:0.8b\"}");
        assertFailure(() -> client().ask(STATE, questions()), FallbackReason.MALFORMED, 200);
    }

    @Test
    @DisplayName("a missing answer for an asked question → MALFORMED")
    void missingAnswer() {
        respondWith(200, """
                { "answers": { "simple_chat": { "type": "noul", "noul": 0.93 } } }
                """);

        assertFailure(() -> client().ask(STATE, questions()), FallbackReason.MALFORMED, 200);
    }

    @Test
    @DisplayName("an unknown choice label → MALFORMED")
    void unknownLabel() {
        respondWith(200, HAPPY_BODY.replace("\"choice\": \"IDLE\"", "\"choice\": \"SHIPPING\""));

        assertFailure(() -> client().ask(STATE, questions()), FallbackReason.MALFORMED, 200);
    }

    @Test
    @DisplayName("a probability for an unknown label, or an unknown score level → MALFORMED")
    void unknownProbabilityLabelOrLevel() {
        respondWith(200, HAPPY_BODY.replace("\"CREATING_PO\": 0.12", "\"SHIPPING\": 0.12"));
        assertFailure(() -> client().ask(STATE, questions()), FallbackReason.MALFORMED, 200);

        respondWith(200, HAPPY_BODY.replace("\"2\": \"HIGH\"", "\"2\": \"EXTREME\""));
        assertFailure(() -> client().ask(STATE, questions()), FallbackReason.MALFORMED, 200);
    }

    @Test
    @DisplayName("a probability outside [0, 1] → MALFORMED, for noul, choice and score alike")
    void probabilityOutOfRange() {
        respondWith(200, HAPPY_BODY.replace("\"noul\": 0.93", "\"noul\": 1.3"));
        assertFailure(() -> client().ask(STATE, questions()), FallbackReason.MALFORMED, 200);

        respondWith(200, HAPPY_BODY.replace("\"confidence\": 0.81", "\"confidence\": -0.2"));
        assertFailure(() -> client().ask(STATE, questions()), FallbackReason.MALFORMED, 200);

        respondWith(200, HAPPY_BODY.replace("\"0\": 0.55", "\"0\": 55"));
        assertFailure(() -> client().ask(STATE, questions()), FallbackReason.MALFORMED, 200);

        respondWith(200, HAPPY_BODY.replace("\"noul\": 0.93", "\"noul\": \"yes\""));
        assertFailure(() -> client().ask(STATE, questions()), FallbackReason.MALFORMED, 200);
    }

    @Test
    @DisplayName("a score answer without probabilities → MALFORMED (the value is the argmax level, spec §2.2)")
    void scoreWithoutProbabilities() {
        respondWith(200, """
                { "answers": {
                    "simple_chat": { "type": "noul", "noul": 0.2 },
                    "workflow_state": { "type": "choice", "choice": "IDLE", "confidence": 0.6 },
                    "risk": { "type": "score", "score": 1.2, "confidence": 0.5 } } }
                """);

        assertFailure(() -> client().ask(STATE, questions()), FallbackReason.MALFORMED, 200);
    }

    @Test
    @DisplayName("every failure log carries the class, status, host, model and latency and never the state")
    void failureLogsCarryNoMessageText() {
        respondWith(500, "{\"error\":\"" + STATE + "\"}");
        assertFailure(() -> client().ask(STATE, questions()), FallbackReason.ERROR, 500);
        respondWith(200, "{\"answers\":{}}");
        assertFailure(() -> client().ask(STATE, questions()), FallbackReason.MALFORMED, 200);

        assertThat(logs.list).isNotEmpty();
        assertThat(logs.list).allMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
        assertThat(logs.list).allMatch(event -> {
            String line = event.getFormattedMessage();
            return line.contains("reason=")
                    && line.contains("status=")
                    && line.contains("host=127.0.0.1:")
                    && line.contains("model=tev1:0.8b")
                    && line.contains("latencyMs=");
        });
        assertNoLogCarriesTheState();
    }

    private void assertFailure(Runnable call, FallbackReason reason, Integer status) {
        assertThatThrownBy(call::run).isInstanceOf(JevProviderException.class).satisfies(thrown -> {
            JevProviderException failure = (JevProviderException) thrown;
            assertThat(failure.reason()).isEqualTo(reason);
            if (status != null) {
                assertThat(failure.httpStatus()).isEqualTo(status);
            }
            assertThat(failure.getMessage()).doesNotContain(STATE).doesNotContain("ACME-7731");
        });
        assertNoLogCarriesTheState();
    }

    private void assertNoLogCarriesTheState() {
        assertThat(logs.list)
                .noneMatch(event -> event.getFormattedMessage().contains("ACME-7731")
                        || event.getFormattedMessage().contains("receivables ledger")
                        || (event.getThrowableProxy() != null
                                && event.getThrowableProxy().getMessage() != null
                                && event.getThrowableProxy().getMessage().contains("ACME-7731")));
    }
}
