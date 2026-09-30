package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.client.JevProviderException;
import com.positivity.mcp.internal.config.TaggingProperties;
import com.positivity.mcp.internal.domain.FallbackReason;
import com.positivity.mcp.internal.domain.QuestionTagger;
import com.positivity.mcp.internal.domain.QuestionTags;
import com.positivity.mcp.internal.domain.TagAnswer;
import com.positivity.mcp.internal.domain.TagName;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ADR-0068 §2, §6 / spec §2.5: builds the one {@link QuestionTags} record of a chat turn.
 *
 * <ol>
 *   <li>Run the heuristic tagger (always; sub-millisecond).
 *   <li>If {@code mode} is not {@code off}, run the decision-model tagger inside the timeout budget.
 *       Any provider failure yields the heuristic record with {@code fallbackReason} set.
 *   <li>Merge per tag. In Wave 1 the acting value is always the heuristic one, whatever {@code mode}
 *       and {@code enforced-tags} say: {@code enforce} lands in Wave 2. Both answers are kept for the
 *       trace.
 * </ol>
 *
 * <p>Never throws: a chat turn never fails because tagging failed (ADR-0068 §2). With {@code mode:
 * off} nothing here touches the meter registry or writes a log line.
 *
 * <p>Package-private: its one caller is {@link ToolSelectionEngine#tag}, and the cross-module ArchUnit
 * rule reserves public {@code *Service} names for {@code internal.service}.
 */
@Component
class TaggingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaggingService.class);

    static final String LATENCY = "mcp.tagging.latency";
    static final String REQUESTS = "mcp.tagging.requests";
    static final String FALLBACK = "mcp.tagging.fallback";
    static final String AGREEMENT = "mcp.tagging.agreement";
    static final String STATE_TRUNCATED = "mcp.tagging.state_truncated";

    private final TaggingProperties properties;
    private final HeuristicQuestionTagger heuristicTagger;
    private final @Nullable QuestionTagger modelTagger;

    /** Null in mode {@code off}: nothing is registered. */
    private final @Nullable MeterRegistry meterRegistry;

    @Autowired
    TaggingService(
            @NonNull TaggingProperties properties,
            @NonNull HeuristicQuestionTagger heuristicTagger,
            @Nullable JevQuestionTagger modelTagger,
            @Nullable MeterRegistry meterRegistry) {
        this(properties, heuristicTagger, (QuestionTagger) modelTagger, meterRegistry);
    }

    /** For tests: any {@link QuestionTagger} as the decision-model side. */
    TaggingService(
            @NonNull TaggingProperties properties,
            @NonNull HeuristicQuestionTagger heuristicTagger,
            @Nullable QuestionTagger modelTagger,
            @Nullable MeterRegistry meterRegistry) {
        this.properties = properties;
        this.heuristicTagger = heuristicTagger;
        this.modelTagger = modelTagger;
        this.meterRegistry = properties.enabled() ? meterRegistry : null;
    }

    public @NonNull TaggingProperties properties() {
        return properties;
    }

    /** The heuristic record alone, whatever the mode: no provider call (the legacy selection overloads). */
    public @NonNull QuestionTags heuristicOnly(@NonNull String message) {
        return heuristicTagger.tag(message);
    }

    /** The turn's record: heuristic always; the decision model beside it when the mode allows. */
    public @NonNull QuestionTags tag(@NonNull String message) {
        QuestionTags heuristic = heuristicTagger.tag(message);
        if (!properties.enabled() || modelTagger == null) {
            return heuristic;
        }
        long startNanos = System.nanoTime();
        try {
            QuestionTags model = modelTagger.tag(message);
            long latencyMs = model.latencyMs() == null ? elapsedMs(startNanos) : model.latencyMs();
            QuestionTags merged = merge(heuristic, model, latencyMs);
            recordSuccess(merged);
            return merged;
        } catch (JevProviderException failure) {
            return fallback(heuristic, failure.reason(), elapsedMs(startNanos), failure);
        } catch (RuntimeException unexpected) {
            return fallback(heuristic, FallbackReason.ERROR, elapsedMs(startNanos), unexpected);
        }
    }

    /**
     * Spec §2.5 step 3, Wave 1 shape: the acting answers are the heuristic ones. Wave 2 replaces a
     * listed tag's acting answer with the model's when {@code mode == enforce} and its confidence
     * meets {@code thresholdFor(tag)}.
     */
    private @NonNull QuestionTags merge(@NonNull QuestionTags heuristic, @NonNull QuestionTags model, long latencyMs) {
        return new QuestionTags(
                properties.mode(),
                heuristic.heuristic(),
                model.model(),
                heuristic.heuristic(),
                null,
                model.providerModel(),
                latencyMs,
                model.stateTruncated());
    }

    private @NonNull QuestionTags fallback(
            @NonNull QuestionTags heuristic,
            @NonNull FallbackReason reason,
            long latencyMs,
            @NonNull RuntimeException cause) {
        if (meterRegistry != null) {
            Counter.builder(REQUESTS)
                    .tag("model", properties.provider().model())
                    .tag("outcome", reason.wireName())
                    .register(meterRegistry)
                    .increment();
            Counter.builder(FALLBACK)
                    .tag("reason", reason.wireName())
                    .register(meterRegistry)
                    .increment();
            Timer.builder(LATENCY)
                    .tag("model", properties.provider().model())
                    .register(meterRegistry)
                    .record(Duration.ofMillis(latencyMs));
        }
        if (!(cause instanceof JevProviderException)) {
            // The client logs its own failures with the detail §4 allows; an unexpected exception
            // from the tagger itself is logged here by class only.
            LOGGER.warn(
                    "MCP tagging failed outside the provider client; heuristic answers used error={}",
                    cause.getClass().getSimpleName());
        }
        return new QuestionTags(
                properties.mode(),
                heuristic.heuristic(),
                Map.of(),
                heuristic.heuristic(),
                reason,
                properties.provider().model(),
                latencyMs,
                false);
    }

    private void recordSuccess(@NonNull QuestionTags merged) {
        if (meterRegistry == null) {
            return;
        }
        String model = merged.providerModel() == null ? properties.provider().model() : merged.providerModel();
        Counter.builder(REQUESTS)
                .tag("model", model)
                .tag("outcome", "ok")
                .register(meterRegistry)
                .increment();
        Timer.builder(LATENCY)
                .tag("model", model)
                .register(meterRegistry)
                .record(Duration.ofMillis(merged.latencyMs() == null ? 0 : merged.latencyMs()));
        if (merged.stateTruncated()) {
            Counter.builder(STATE_TRUNCATED).register(meterRegistry).increment();
        }
        for (Map.Entry<String, TagAnswer> entry : merged.heuristic().entrySet()) {
            TagAnswer modelAnswer = merged.model().get(entry.getKey());
            if (modelAnswer == null) {
                continue;
            }
            // Entity groups never reach here: the heuristic answers no entity tag.
            String tag =
                    TagName.fromWireName(entry.getKey()).map(TagName::wireName).orElse(entry.getKey());
            Counter.builder(AGREEMENT)
                    .tag("tag", tag)
                    .tag("agree", Boolean.toString(QuestionTags.agrees(entry.getValue(), modelAnswer)))
                    .register(meterRegistry)
                    .increment();
        }
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
