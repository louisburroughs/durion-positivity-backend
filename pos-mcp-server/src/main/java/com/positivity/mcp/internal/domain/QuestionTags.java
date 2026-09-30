package com.positivity.mcp.internal.domain;

import com.positivity.mcp.internal.enums.NltiIntentType;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.TreeMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * ADR-0068 §1: the immutable per-turn tag record every consumer reads its decision from. One value
 * and one confidence per tag, plus the tagger that produced it; when both taggers ran (spec §2.5),
 * both answers are kept so the trace can record agreement.
 *
 * <p>Consumers read {@link #acting()} through the typed accessors below. In {@code off} and {@code
 * shadow} the acting answer is always the heuristic one; in {@code enforce} (Wave 2) it is the model's
 * for a listed tag at or above its threshold. A tag with no acting answer yields the safe default of
 * its consumer: not simple chat, {@link WorkflowState#DEFAULT}, no tool added, {@link
 * RouterClassification#safeDefault()}, no entity seed.
 *
 * @param mode the mode that produced this record; {@link TaggingMode#OFF} for a heuristic-only or
 *     empty record
 * @param heuristic the heuristic tagger's answers by wire name; empty only for {@link #none()}
 * @param model the decision model's answers by wire name; empty in {@code off} and after a provider
 *     failure
 * @param acting the answers consumers read, by wire name
 * @param fallbackReason why the model's answers were not used for the turn, or null
 * @param providerModel the model that answered, when the provider was called
 * @param latencyMs the provider call's wall time, when it was called (also on failure)
 * @param stateTruncated whether the message was cut at {@code max-state-chars} before it was sent
 * @param questionCount how many questions the provider was asked, when it was called
 * @param requestBodyBytes the size of the request body sent, when the provider was called
 * @param optionListHash the hash of the option lists asked (domain options and entity keys), when
 *     the provider was called; agreement on those tags is comparable within one hash only
 */
public record QuestionTags(
        @NonNull TaggingMode mode,
        @NonNull Map<String, TagAnswer> heuristic,
        @NonNull Map<String, TagAnswer> model,
        @NonNull Map<String, TagAnswer> acting,
        @Nullable FallbackReason fallbackReason,
        @Nullable String providerModel,
        @Nullable Long latencyMs,
        boolean stateTruncated,
        @Nullable Integer questionCount,
        @Nullable Integer requestBodyBytes,
        @Nullable String optionListHash) {

    private static final QuestionTags NONE =
            new QuestionTags(TaggingMode.OFF, Map.of(), Map.of(), Map.of(), null, null, null, false);

    public QuestionTags {
        heuristic = sorted(heuristic);
        model = sorted(model);
        acting = sorted(acting);
    }

    /** Without the request-cost fields (a heuristic or fallback record). */
    public QuestionTags(
            @NonNull TaggingMode mode,
            @NonNull Map<String, TagAnswer> heuristic,
            @NonNull Map<String, TagAnswer> model,
            @NonNull Map<String, TagAnswer> acting,
            @Nullable FallbackReason fallbackReason,
            @Nullable String providerModel,
            @Nullable Long latencyMs,
            boolean stateTruncated) {
        this(
                mode,
                heuristic,
                model,
                acting,
                fallbackReason,
                providerModel,
                latencyMs,
                stateTruncated,
                null,
                null,
                null);
    }

    /**
     * No tags at all: what warm-up passes (spec §2.5) and what a consumer receives outside a chat
     * turn. Every accessor answers its safe default.
     */
    public static @NonNull QuestionTags none() {
        return NONE;
    }

    /** A heuristic-only record: the acting answers are the heuristic ones (mode {@code off}). */
    public static @NonNull QuestionTags heuristic(@NonNull Map<String, TagAnswer> answers) {
        return new QuestionTags(TaggingMode.OFF, answers, Map.of(), answers, null, null, null, false);
    }

    public boolean isNone() {
        return acting.isEmpty() && heuristic.isEmpty() && model.isEmpty();
    }

    /** The acting answer of {@code tag}; for {@link TagName#ENTITY} use {@link #entitySeeds()}. */
    public @NonNull Optional<TagAnswer> answer(@NonNull TagName tag) {
        return Optional.ofNullable(acting.get(tag.wireName()));
    }

    /** True when the acting Noul answer of {@code tag} is {@code true}; false when absent. */
    public boolean is(@NonNull TagName tag) {
        return answer(tag).map(TagAnswer::isTrue).orElse(false);
    }

    public boolean simpleChat() {
        return is(TagName.SIMPLE_CHAT);
    }

    public boolean followsPreviousTurn() {
        return is(TagName.FOLLOWS_PREVIOUS_TURN);
    }

    public boolean needsWebSearch() {
        return is(TagName.NEEDS_WEB_SEARCH);
    }

    public boolean aboutInventory() {
        return is(TagName.ABOUT_INVENTORY);
    }

    public boolean aboutOrders() {
        return is(TagName.ABOUT_ORDERS);
    }

    public boolean impliesDateWindow() {
        return is(TagName.IMPLIES_DATE_WINDOW);
    }

    public boolean adminAccountQuestion() {
        return is(TagName.ADMIN_ACCOUNT_QUESTION);
    }

    public boolean compoundQuestion() {
        return is(TagName.COMPOUND_QUESTION);
    }

    /** The acting workflow state; {@link WorkflowState#DEFAULT} when absent or not a known state. */
    public @NonNull WorkflowState workflowState() {
        return answer(TagName.WORKFLOW_STATE)
                .flatMap(answer -> enumOf(WorkflowState.class, answer.value()))
                .orElse(WorkflowState.DEFAULT);
    }

    public @NonNull NltiIntentType intent() {
        return answer(TagName.INTENT)
                .flatMap(answer -> enumOf(NltiIntentType.class, answer.value()))
                .orElse(RouterClassification.safeDefault().intentType());
    }

    public @NonNull NltiRiskLevel risk() {
        return answer(TagName.RISK)
                .flatMap(answer -> enumOf(NltiRiskLevel.class, answer.value()))
                .orElse(RouterClassification.safeDefault().riskLevel());
    }

    public @NonNull RequestComplexity complexity() {
        return answer(TagName.COMPLEXITY)
                .flatMap(answer -> enumOf(RequestComplexity.class, answer.value()))
                .orElse(RouterClassification.safeDefault().complexity());
    }

    public @NonNull String domain() {
        return answer(TagName.DOMAIN)
                .map(TagAnswer::value)
                .orElse(RouterClassification.safeDefault().domain());
    }

    /** The router-derived tags as one classification (ADR-0068 §7); {@code safeDefault()} when absent. */
    public @NonNull RouterClassification routerClassification() {
        return new RouterClassification(intent(), risk(), complexity(), domain());
    }

    /**
     * The lexicon entity keys whose acting {@code entity_<key>} Noul is {@code true}, in key order.
     * Empty for a heuristic record, which answers no entity tag (ADR-0069 §5.1 seeds those from the
     * lexicon terms already).
     */
    public @NonNull List<String> entitySeeds() {
        List<String> seeds = new ArrayList<>();
        acting.forEach((name, answer) -> {
            if (answer.isTrue()) {
                TagName.entityKey(name).ifPresent(seeds::add);
            }
        });
        return List.copyOf(seeds);
    }

    /** True when the decision model answered this turn (whatever acted on it). */
    public boolean modelAnswered() {
        return !model.isEmpty();
    }

    /**
     * The share of tags both taggers answered on which they agree; empty unless both answered at least
     * one tag in common (the heuristic answers no entity tag, so those never count).
     */
    public @NonNull OptionalDouble agreementRate() {
        int compared = 0;
        int agreed = 0;
        for (Map.Entry<String, TagAnswer> entry : heuristic.entrySet()) {
            TagAnswer other = model.get(entry.getKey());
            if (other == null) {
                continue;
            }
            compared++;
            if (agrees(entry.getValue(), other)) {
                agreed++;
            }
        }
        return compared == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) agreed / compared);
    }

    /** Whether the two taggers' answers for one tag agree on the value (confidence is not compared). */
    public static boolean agrees(@NonNull TagAnswer left, @NonNull TagAnswer right) {
        return left.value().equalsIgnoreCase(right.value());
    }

    private static <E extends Enum<E>> Optional<E> enumOf(Class<E> type, String value) {
        try {
            return Optional.of(Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    private static @NonNull Map<String, TagAnswer> sorted(@NonNull Map<String, TagAnswer> answers) {
        return java.util.Collections.unmodifiableMap(new TreeMap<>(answers));
    }
}
