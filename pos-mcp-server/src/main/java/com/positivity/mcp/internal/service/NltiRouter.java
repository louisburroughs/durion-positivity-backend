package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.ModelTier;
import com.positivity.mcp.internal.domain.QuestionTags;
import com.positivity.mcp.internal.domain.RouterClassification;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Gate 4 T1 router, since ADR-0068 §7 mapped from the turn's tags. Classifies a request (intent /
 * risk / complexity / domain) from the acting {@code intent}, {@code risk}, {@code complexity} and
 * {@code domain} tags of its {@link QuestionTags} record, then maps the classification to a {@link
 * ModelTier} through the unchanged {@link TierSelector}.
 *
 * <p>The chat-model call of the original router is gone (ADR-0068 §7): nothing here generates or
 * parses text. The {@code routerChatModel} bean and {@code mcp.model.router} stay defined until the
 * router tags are promoted, then go.
 *
 * <p>Safety (ADR-0068 §3.5): a tag that is unlisted, below its threshold or absent takes {@link
 * RouterClassification#safeDefault()}'s value for that field, per field, so a low-confidence {@code
 * risk} is {@code HIGH}, an unknown {@code intent} is {@code UNKNOWN}, and the tier is {@link
 * ModelTier#T2_COMPLEX}; risk never downgrades. {@link QuestionTags#none()} routes to the safe default.
 */
@Component
@Profile("alpha")
public class NltiRouter {

    /** A router run: the classification and the tier it selects. */
    public record RoutingDecision(
            @NonNull RouterClassification classification,
            @NonNull ModelTier tier) {}

    private final TierSelector tierSelector;

    public NltiRouter(@NonNull TierSelector tierSelector) {
        this.tierSelector = tierSelector;
    }

    /** Routes a request without a tag record: the safe default, {@link ModelTier#T2_COMPLEX}. */
    public @NonNull ModelTier route(@NonNull String message) {
        return classify(message).tier();
    }

    /** Classifies a request without a tag record: the safe default classification and its tier. */
    public @NonNull RoutingDecision classify(@NonNull String message) {
        return classify(message, QuestionTags.none());
    }

    /**
     * ADR-0068 §7: the classification is the record's {@link QuestionTags#routerClassification()}, the
     * acting router tags with {@code safeDefault()} per absent or below-threshold field; the tier is
     * {@link TierSelector#select}. Never throws and never calls a model. {@code message} is unused
     * since the chat call was removed and is kept so the managers' call shape does not change.
     */
    @SuppressWarnings("unused")
    public @NonNull RoutingDecision classify(@NonNull String message, @NonNull QuestionTags tags) {
        RouterClassification classification = tags.routerClassification();
        return new RoutingDecision(classification, tierSelector.select(classification));
    }
}
