package com.positivity.mcp.internal.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.config.TaggingEnabledCondition;
import com.positivity.mcp.internal.config.TaggingProperties;
import com.positivity.mcp.internal.domain.FallbackReason;
import com.positivity.mcp.internal.domain.TagQuestion;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * ADR-0068 §5: the thin System One client. Speaks {@code POST {base-url}/v1/systemone} as Ollama 0.35
 * and TypeSafe publish it, through a plain JDK {@link HttpClient} (never {@code @LoadBalanced}, no
 * interceptors). No retries, no circuit state: every failure is one {@link JevProviderException} and
 * the caller falls back to the heuristic record (§2).
 *
 * <p>The latency budget {@code mcp.tagging.provider.timeout} is ONE overall deadline for the call:
 * connect, request, response headers and the whole response body. A per-phase connect or read timeout
 * cannot give that (each phase could take the full budget, and a read timeout restarts on every byte
 * of a trickled body), so the call is sent asynchronously and the calling thread waits for it at most
 * the budget, measured from the start of the call; on expiry the exchange is cancelled and the turn
 * takes the heuristic answers ({@code TIMEOUT}). The caller stays synchronous: it never waits longer
 * than the budget, and nothing outlives the call but the client's own I/O, which the cancellation
 * aborts.
 *
 * <p>Data minimisation (§4): the request body carries exactly {@code model}, {@code state}, {@code
 * keep_alive} (when set) and {@code questions}. Nothing about the caller, the tenant or the
 * conversation is sent. The {@code state} is the message cut at {@code max-state-chars}.
 *
 * <p>Logging (§4): this class never logs the state, an answer string or the provider's own error text
 * at any level. A failure log carries the failure class, the HTTP status, the provider host, the
 * model and the latency; of an Ollama {@code {"error": …}} body only the length of the text is
 * logged, because a server message about the request cannot be shown never to echo the state.
 */
@Component
@Conditional(TaggingEnabledCondition.class)
public class JevClient implements DisposableBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(JevClient.class);

    static final String SYSTEM_ONE_PATH = "/v1/systemone";

    /** Unknown fields (a newer provider's additions, {@code usage}) are ignored, not failures. */
    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final HttpClient httpClient;
    private final TaggingProperties properties;

    @Autowired
    public JevClient(@NonNull TaggingProperties properties) {
        this(buildHttpClient(properties.provider()), properties);
    }

    /** Visible for testing: a pre-built client. */
    JevClient(@NonNull HttpClient httpClient, @NonNull TaggingProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    private static @NonNull HttpClient buildHttpClient(TaggingProperties.@NonNull Provider provider) {
        // A client of its own, not a shared or load-balanced one: no bearer-token relay, no Eureka
        // lookup, no interceptor may add a header about the caller (ADR-0068 §4, §5). HTTP/1.1, as
        // Ollama serves it (no h2c upgrade attempt); no redirects (a redirect could take the state off
        // the configured host). The connect timeout only fails a dead host early; the overall
        // deadline in ask() bounds the call.
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(provider.timeout())
                .build();
    }

    /** Aborts any call still in flight when the context closes. */
    @Override
    public void destroy() {
        httpClient.shutdownNow();
    }

    /**
     * The result of one call.
     *
     * @param model the configured {@code mcp.tagging.provider.model}; never the model string the
     *     provider reports (ADR-0068 §3.6: no response string is used)
     * @param answers one validated answer per question asked, by wire name
     * @param latencyMs wall time of the call
     * @param stateTruncated whether the state was cut at {@code max-state-chars}
     * @param requestBodyBytes the size of the JSON body sent, for the cost of the wide request
     */
    public record JevResponse(
            @NonNull String model,
            @NonNull Map<String, JevAnswer> answers,
            long latencyMs,
            boolean stateTruncated,
            int requestBodyBytes) {
        public JevResponse {
            answers = Map.copyOf(answers);
        }
    }

    /**
     * Asks {@code questions} about {@code state}.
     *
     * @throws JevProviderException on any failure, with the reason the fallback is counted under
     */
    public @NonNull JevResponse ask(@NonNull String state, @NonNull List<TagQuestion> questions) {
        TaggingProperties.Provider provider = properties.provider();
        long startNanos = System.nanoTime();
        boolean truncated = properties.truncates(state);
        String sent = truncated ? state.substring(0, properties.maxStateChars()) : state;
        int status = -1;
        try {
            byte[] body = MAPPER.writeValueAsBytes(requestBody(provider, sent, questions));
            Exchange exchange = exchange(provider, body, startNanos);
            status = exchange.status();
            long latencyMs = elapsedMs(startNanos);
            JevResponse parsed = parse(exchange, provider, questions, latencyMs, truncated, body.length);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "MCP tagging provider answered model={} host={} questions={} latencyMs={} stateTruncated={}",
                        provider.model(),
                        provider.host(),
                        questions.size(),
                        latencyMs,
                        truncated);
            }
            return parsed;
        } catch (JevProviderException failure) {
            logFailure(failure, provider, status, elapsedMs(startNanos));
            throw failure;
        } catch (JsonProcessingException serialization) {
            JevProviderException failure = new JevProviderException(
                    FallbackReason.ERROR, "request could not be serialised", null, serialization);
            logFailure(failure, provider, status, elapsedMs(startNanos));
            throw failure;
        } catch (RuntimeException unexpected) {
            JevProviderException failure = new JevProviderException(
                    FallbackReason.ERROR, "provider call failed: " + rootClass(unexpected), null, unexpected);
            logFailure(failure, provider, status, elapsedMs(startNanos));
            throw failure;
        }
    }

    private record Exchange(int status, byte @NonNull [] body) {}

    /**
     * One POST under one overall deadline: the budget less what the call has already spent, so
     * connect, headers and a trickled body together never exceed {@code provider.timeout}.
     */
    private @NonNull Exchange exchange(
            TaggingProperties.@NonNull Provider provider, byte @NonNull [] body, long startNanos) {
        Duration budget = provider.timeout();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(provider.baseUrl() + SYSTEM_ONE_PATH))
                .timeout(budget)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (provider.apiKey() != null) {
            request.header("Authorization", "Bearer " + provider.apiKey());
        }
        CompletableFuture<HttpResponse<byte[]>> pending =
                httpClient.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        try {
            long remainingNanos = Math.max(0L, budget.toNanos() - (System.nanoTime() - startNanos));
            HttpResponse<byte[]> response = pending.get(remainingNanos, TimeUnit.NANOSECONDS);
            return new Exchange(response.statusCode(), response.body() == null ? new byte[0] : response.body());
        } catch (TimeoutException expired) {
            pending.cancel(true);
            throw new JevProviderException(
                    FallbackReason.TIMEOUT, "provider exceeded the timeout budget", null, expired);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            throw new JevProviderException(
                    isTimeout(cause) ? FallbackReason.TIMEOUT : FallbackReason.ERROR,
                    "provider transport failure: " + rootClass(cause),
                    null,
                    cause);
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new JevProviderException(FallbackReason.ERROR, "provider call interrupted", null, interrupted);
        }
    }

    /** Spec §2.2 / ADR-0068 §4: exactly {@code model}, {@code state}, {@code keep_alive}, {@code questions}. */
    public static @NonNull Map<String, Object> requestBody(
            TaggingProperties.@NonNull Provider provider, @NonNull String state, @NonNull List<TagQuestion> questions) {
        SequencedMap<String, Object> body = new LinkedHashMap<>();
        body.put("model", provider.model());
        body.put("state", state);
        if (provider.keepAlive() != null) {
            body.put("keep_alive", provider.keepAlive());
        }
        SequencedMap<String, Object> wireQuestions = new LinkedHashMap<>();
        for (TagQuestion question : questions) {
            wireQuestions.put(question.wireName(), question.toWire());
        }
        body.put("questions", wireQuestions);
        return body;
    }

    private static @NonNull JevResponse parse(
            @NonNull Exchange exchange,
            TaggingProperties.@NonNull Provider provider,
            @NonNull List<TagQuestion> questions,
            long latencyMs,
            boolean truncated,
            int requestBodyBytes) {
        int status = exchange.status();
        // The status decides before the body is looked at: a 429 or 529 is rate limiting whatever
        // its body says, an empty one included.
        if (status == 429 || status == 529) {
            throw new JevProviderException(FallbackReason.RATE_LIMITED, "provider rate limited", status, null);
        }
        if (status < 200 || status >= 300) {
            throw new JevProviderException(FallbackReason.ERROR, "provider status " + status, status, null);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(exchange.body());
        } catch (java.io.IOException malformed) {
            throw new JevProviderException(FallbackReason.MALFORMED, "response is not JSON", status, null);
        }
        if (root == null || !root.isObject()) {
            throw new JevProviderException(FallbackReason.MALFORMED, "response is not a JSON object", status, null);
        }
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            // Ollama reports a request or model problem as {"error": "..."} with a 2xx (ADR-0068 §5).
            // Only the text's length is logged (§4).
            throw new JevProviderException(
                    FallbackReason.ERROR,
                    "provider error body (errorTextLength=" + error.asText("").length() + ")",
                    status,
                    null);
        }
        JsonNode answers = root.get("answers");
        if (answers == null || !answers.isObject()) {
            throw new JevProviderException(FallbackReason.MALFORMED, "response has no answers object", status, null);
        }
        Map<String, JevAnswer> parsed = new LinkedHashMap<>();
        for (TagQuestion question : questions) {
            JsonNode answer = answers.get(question.wireName());
            if (answer == null || !answer.isObject()) {
                throw new JevProviderException(
                        FallbackReason.MALFORMED, "missing answer for " + question.wireName(), status, null);
            }
            parsed.put(question.wireName(), parseAnswer(question, answer, status));
        }
        // ADR-0068 §3.6: only typed values are read from the response. The provider's own "model"
        // string is ignored; the configured model names the answer in the trace, logs and meters.
        return new JevResponse(provider.model(), parsed, latencyMs, truncated, requestBodyBytes);
    }

    private static @NonNull JevAnswer parseAnswer(@NonNull TagQuestion question, @NonNull JsonNode answer, int status) {
        String name = question.wireName();
        return switch (question.primitive()) {
            case NOUL -> new JevAnswer.Noul(probability(answer.get("noul"), name, status));
            case CHOICE -> {
                String label = answer.path("choice").asText(null);
                if (label == null || !question.options().contains(label)) {
                    throw new JevProviderException(
                            FallbackReason.MALFORMED, "unknown or missing choice label for " + name, status, null);
                }
                Map<String, Double> probabilities =
                        probabilities(answer.get("probabilities"), question.options(), name, status);
                yield new JevAnswer.Choice(label, probabilities, probability(answer.get("confidence"), name, status));
            }
            case SCORE -> {
                Map<String, String> legend = legend(answer.get("legend"), question.options(), name, status);
                JsonNode rawProbabilities = answer.get("probabilities");
                if (rawProbabilities == null || !rawProbabilities.isObject() || rawProbabilities.isEmpty()) {
                    throw new JevProviderException(
                            FallbackReason.MALFORMED, "missing score probabilities for " + name, status, null);
                }
                Map<String, Double> byLevel = new LinkedHashMap<>();
                for (Iterator<Map.Entry<String, JsonNode>> it = rawProbabilities.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    String level = legend.getOrDefault(entry.getKey(), entry.getKey());
                    if (!question.options().contains(level)) {
                        throw new JevProviderException(
                                FallbackReason.MALFORMED, "unknown score level for " + name, status, null);
                    }
                    byLevel.put(level, probability(entry.getValue(), name, status));
                }
                // Argmax; ties go to the higher level (spec §2.2), so scan the levels lowest first
                // and let an equal probability replace the earlier level.
                String best = null;
                double bestProbability = -1.0;
                for (String level : question.options()) {
                    Double probability = byLevel.get(level);
                    if (probability != null && probability >= bestProbability) {
                        best = level;
                        bestProbability = probability;
                    }
                }
                if (best == null) {
                    throw new JevProviderException(
                            FallbackReason.MALFORMED, "score probabilities name no level for " + name, status, null);
                }
                JsonNode score = answer.get("score");
                Double weighted = score == null || !score.isNumber() ? null : score.asDouble();
                yield new JevAnswer.Score(best, byLevel, probability(answer.get("confidence"), name, status), weighted);
            }
        };
    }

    /** A level index → level label map from the answer's {@code legend}, else the question's own order. */
    private static @NonNull Map<String, String> legend(
            @Nullable JsonNode legend, @NonNull List<String> levels, @NonNull String name, int status) {
        Map<String, String> byIndex = new LinkedHashMap<>();
        for (int index = 0; index < levels.size(); index++) {
            byIndex.put(Integer.toString(index), levels.get(index));
        }
        if (legend != null && legend.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = legend.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                String level = entry.getValue().asText("");
                if (!levels.contains(level)) {
                    throw new JevProviderException(
                            FallbackReason.MALFORMED, "legend names an unknown level for " + name, status, null);
                }
                byIndex.put(entry.getKey(), level);
            }
        }
        return byIndex;
    }

    private static @NonNull Map<String, Double> probabilities(
            @Nullable JsonNode node, @NonNull List<String> labels, @NonNull String name, int status) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new JevProviderException(
                    FallbackReason.MALFORMED, "probabilities not an object for " + name, status, null);
        }
        Map<String, Double> probabilities = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (!labels.contains(entry.getKey())) {
                throw new JevProviderException(
                        FallbackReason.MALFORMED, "probability for an unknown label for " + name, status, null);
            }
            probabilities.put(entry.getKey(), probability(entry.getValue(), name, status));
        }
        return probabilities;
    }

    private static double probability(@Nullable JsonNode node, @NonNull String name, int status) {
        if (node == null || !node.isNumber()) {
            throw new JevProviderException(FallbackReason.MALFORMED, "missing probability for " + name, status, null);
        }
        double value = node.asDouble();
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new JevProviderException(
                    FallbackReason.MALFORMED, "probability outside [0, 1] for " + name, status, null);
        }
        return value;
    }

    private static boolean isTimeout(@NonNull Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth++ < 10) {
            if (current instanceof SocketTimeoutException
                    || current instanceof TimeoutException
                    || current instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static @NonNull String rootClass(@NonNull Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current.getCause() != null && depth++ < 10) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    /** ADR-0068 §4: failure class, status, host, model and latency; never the state or an answer. */
    private static void logFailure(
            @NonNull JevProviderException failure,
            TaggingProperties.@NonNull Provider provider,
            int status,
            long latencyMs) {
        LOGGER.warn(
                "MCP tagging provider failure reason={} error={} detail=\"{}\" status={} host={} model={} latencyMs={}",
                failure.reason().wireName(),
                failure.getCause() == null ? failure.getClass().getSimpleName() : rootClass(failure.getCause()),
                failure.getMessage(),
                status < 0 ? "none" : status,
                provider.host(),
                provider.model(),
                latencyMs);
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
