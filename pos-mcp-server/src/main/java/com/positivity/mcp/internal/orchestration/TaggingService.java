package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.client.JevProviderException;
import com.positivity.mcp.internal.config.TaggingProperties;
import com.positivity.mcp.internal.domain.FallbackReason;
import com.positivity.mcp.internal.domain.QuestionTagger;
import com.positivity.mcp.internal.domain.QuestionTags;
import com.positivity.mcp.internal.domain.TagAnswer;
import com.positivity.mcp.internal.domain.TagName;
import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.enums.NltiIntentType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
 *   <li>Merge per tag (spec §2.5 step 3). The acting value is the model's when {@code mode} is {@code
 *       enforce}, the tag is listed in {@code enforced-tags}, its confidence meets its threshold and,
 *       for a {@code :veto} entry, the heuristic said {@code true} (the model may only turn {@code
 *       true} into {@code false}); otherwise the heuristic's, with {@code low_confidence} recorded for
 *       a listed tag the model answered below threshold. {@code admin_account_question} is veto-only
 *       whatever the list says (ADR-0068 §3.4); a non-{@code IDLE} {@code workflow_state} must also
 *       meet {@code thresholds.workflow_state.non-idle} (spec §2.6). Both answers are kept for the
 *       trace.
 *   <li>Where the scope graph's {@code lookups} consumer is enforced and the acting {@code intent} is
 *       {@code ACTION}, the heuristic {@code workflow_state} is re-read from the lexicon (ADR-0068
 *       §3.3, the graph lookup step; spec §2.6), after the merge because the acting intent is known
 *       only then; it acts unless the model's answer already does.
 * </ol>
 *
 * <p>Spec §2.5, the T0 skip: when the heuristic {@code simple_chat} fired on an exact catalog rule the
 * provider is not called at all; the record carries {@code fallbackReason = heuristic_certain} and a
 * zero latency.
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
    /** Per-tag {@code low_confidence} fallbacks in {@code enforce}, by tag (spec §2.5). */
    static final String LOW_CONFIDENCE = "mcp.tagging.low_confidence";
    /** Turns on which the provider was not called because the heuristic was certain (spec §2.5). */
    static final String SKIPPED = "mcp.tagging.skipped";

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
        if (HeuristicQuestionTagger.isCertainSimpleChat(heuristic)) {
            return skipped(heuristic);
        }
        long startNanos = System.nanoTime();
        try {
            QuestionTags model = modelTagger.tag(message);
            long latencyMs = model.latencyMs() == null ? elapsedMs(startNanos) : model.latencyMs();
            QuestionTags merged = withLexiconWorkflowState(merge(heuristic, model, latencyMs), message);
            recordSuccess(merged);
            return merged;
        } catch (JevProviderException failure) {
            return fallback(message, heuristic, failure.reason(), elapsedMs(startNanos), failure);
        } catch (RuntimeException unexpected) {
            return fallback(message, heuristic, FallbackReason.ERROR, elapsedMs(startNanos), unexpected);
        }
    }

    /**
     * Spec §2.5 step 3: the acting answer per tag. Every heuristic answer is visited, then the
     * model-only ones (the {@code entity_<key>} Nouls, which the heuristic never answers). The provider
     * model is always the configured one (ADR-0068 §3.6: no response string is used), whatever the
     * tagger reported.
     */
    private @NonNull QuestionTags merge(@NonNull QuestionTags heuristic, @NonNull QuestionTags model, long latencyMs) {
        Map<String, TagAnswer> acting = new LinkedHashMap<>();
        Map<String, FallbackReason> tagFallbacks = new LinkedHashMap<>();
        heuristic.heuristic().forEach((name, heuristicAnswer) -> {
            TagAnswer modelAnswer = model.model().get(name);
            acting.put(name, actingAnswer(name, heuristicAnswer, modelAnswer, tagFallbacks));
        });
        model.model().forEach((name, modelAnswer) -> {
            if (heuristic.heuristic().containsKey(name) || !properties.enforces(name)) {
                return;
            }
            if (meetsThreshold(name, modelAnswer)) {
                acting.put(name, modelAnswer);
            } else {
                tagFallbacks.put(name, FallbackReason.LOW_CONFIDENCE);
            }
        });
        return new QuestionTags(
                properties.mode(),
                heuristic.heuristic(),
                model.model(),
                acting,
                null,
                properties.provider().model(),
                latencyMs,
                model.stateTruncated(),
                model.questionCount(),
                model.requestBodyBytes(),
                model.optionListHash(),
                tagFallbacks);
    }

    /**
     * ADR-0068 §6 / spec §2.5: the model's answer acts when the mode is {@code enforce}, the tag is
     * listed, the confidence meets the threshold (for {@code workflow_state} a non-{@code IDLE} answer
     * also meets {@code non-idle}) and the direction allows it; otherwise the heuristic's.
     */
    private @NonNull TagAnswer actingAnswer(
            @NonNull String name,
            @NonNull TagAnswer heuristicAnswer,
            @Nullable TagAnswer modelAnswer,
            @NonNull Map<String, FallbackReason> tagFallbacks) {
        if (modelAnswer == null || !properties.enforces(name)) {
            return heuristicAnswer;
        }
        TagName tag = TagName.fromWireName(name).orElse(null);
        if (tag == null) {
            return heuristicAnswer;
        }
        if (!meetsThreshold(name, modelAnswer)) {
            tagFallbacks.put(name, FallbackReason.LOW_CONFIDENCE);
            return heuristicAnswer;
        }
        if (properties.directionOf(tag) == TaggingProperties.Direction.VETO) {
            // Acting value = heuristic AND model (spec §2.1): a heuristic false stands whatever the
            // model says; a heuristic true is the model's to keep or veto (ADR-0068 §3.4).
            return heuristicAnswer.isTrue() ? modelAnswer : heuristicAnswer;
        }
        return modelAnswer;
    }

    /** The tag's threshold, and for a non-{@code IDLE} workflow answer the stricter one too (spec §2.6). */
    private boolean meetsThreshold(@NonNull String name, @NonNull TagAnswer modelAnswer) {
        if (modelAnswer.confidence() < properties.thresholdFor(name)) {
            return false;
        }
        if (TagName.WORKFLOW_STATE.wireName().equals(name)
                && !WorkflowState.IDLE
                        .name()
                        .equalsIgnoreCase(modelAnswer.value().trim())) {
            return modelAnswer.confidence() >= properties.nonIdleThreshold();
        }
        return true;
    }

    /**
     * ADR-0068 §3.3 (the graph lookup step) / spec §2.6: with {@code lookups} enforced and the acting
     * {@code intent} {@code ACTION}, the lexicon's {@code workflow_state} for an entity the message
     * names replaces the phrase match as the heuristic answer; it acts unless the model's answer
     * already does (the tag at or above threshold comes first in the chain). In {@code off} and {@code
     * shadow} the acting intent is the heuristic {@code UNKNOWN}, so nothing changes there.
     */
    private @NonNull QuestionTags withLexiconWorkflowState(@NonNull QuestionTags merged, @NonNull String message) {
        if (merged.intent() != NltiIntentType.ACTION || !heuristicTagger.lexiconLookupEnforced()) {
            return merged;
        }
        Optional<TagAnswer> lookup = heuristicTagger.lexiconWorkflowState(message);
        if (lookup.isEmpty()) {
            return merged;
        }
        String name = TagName.WORKFLOW_STATE.wireName();
        Map<String, TagAnswer> heuristic = new LinkedHashMap<>(merged.heuristic());
        heuristic.put(name, lookup.get());
        Map<String, TagAnswer> acting = new LinkedHashMap<>(merged.acting());
        if (!merged.enforced(TagName.WORKFLOW_STATE)) {
            acting.put(name, lookup.get());
        }
        return new QuestionTags(
                merged.mode(),
                heuristic,
                merged.model(),
                acting,
                merged.fallbackReason(),
                merged.providerModel(),
                merged.latencyMs(),
                merged.stateTruncated(),
                merged.questionCount(),
                merged.requestBodyBytes(),
                merged.optionListHash(),
                merged.tagFallbackReasons());
    }

    /** Spec §2.5, the T0 skip: no provider call; the heuristic record, marked {@code heuristic_certain}. */
    private @NonNull QuestionTags skipped(@NonNull QuestionTags heuristic) {
        if (meterRegistry != null) {
            Counter.builder(SKIPPED)
                    .tag("reason", FallbackReason.HEURISTIC_CERTAIN.wireName())
                    .register(meterRegistry)
                    .increment();
        }
        return new QuestionTags(
                properties.mode(),
                heuristic.heuristic(),
                Map.of(),
                heuristic.heuristic(),
                FallbackReason.HEURISTIC_CERTAIN,
                null,
                0L,
                false);
    }

    /**
     * The heuristic record of a turn whose provider call failed. {@code stateTruncated} is the real
     * cut: a message over {@code max-state-chars} was cut before it was sent, whether or not the call
     * then succeeded, and the cut is counted as on success.
     */
    private @NonNull QuestionTags fallback(
            @NonNull String message,
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
        boolean truncated = properties.truncates(message);
        if (meterRegistry != null && truncated) {
            Counter.builder(STATE_TRUNCATED).register(meterRegistry).increment();
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
                truncated);
    }

    private void recordSuccess(@NonNull QuestionTags merged) {
        if (meterRegistry == null) {
            return;
        }
        // ADR-0068 §3.6: the meter tag is the configured model, never a string from the response.
        String model = properties.provider().model();
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
        merged.tagFallbackReasons()
                .forEach((name, reason) -> Counter.builder(LOW_CONFIDENCE)
                        .tag(
                                "tag",
                                TagName.fromWireName(name)
                                        .map(TagName::wireName)
                                        .orElse(name))
                        .register(meterRegistry)
                        .increment());
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
