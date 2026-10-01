package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.domain.ModelTier;
import com.positivity.mcp.internal.domain.QuestionTags;
import com.positivity.mcp.internal.domain.RequestComplexity;
import com.positivity.mcp.internal.domain.RouterClassification;
import com.positivity.mcp.internal.domain.TagAnswer;
import com.positivity.mcp.internal.domain.TagName;
import com.positivity.mcp.internal.domain.TagSource;
import com.positivity.mcp.internal.domain.TaggingMode;
import com.positivity.mcp.internal.enums.NltiIntentType;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-0068 §7 / spec §2.6: the router maps the acting {@code intent}, {@code risk}, {@code complexity}
 * and {@code domain} tags to a classification and a tier; no chat model is involved. §3.5: a field
 * the model did not decide takes {@code safeDefault()}'s value, so risk never downgrades.
 */
class NltiRouterTest {

    private final NltiRouter router = new NltiRouter(new TierSelector());

    /** A record whose acting router tags are the model's (as {@code enforce} at or above threshold yields). */
    private static QuestionTags acting(Map<TagName, String> values) {
        Map<String, TagAnswer> answers = new LinkedHashMap<>();
        values.forEach((tag, value) -> answers.put(tag.wireName(), new TagAnswer(value, 0.95, TagSource.JEV)));
        return new QuestionTags(TaggingMode.ENFORCE, Map.of(), answers, answers, null, "stub", 10L, false);
    }

    @Test
    @DisplayName("all four router tags acting: mapped per field, a simple read routes to T2_SIMPLE")
    void mapsEveryFieldFromTheTags() {
        QuestionTags tags = acting(Map.of(
                TagName.INTENT, "QUERY",
                TagName.RISK, "LOW",
                TagName.COMPLEXITY, "SINGLE_LOOKUP",
                TagName.DOMAIN, "workorder"));

        NltiRouter.RoutingDecision decision = router.classify("look up WO-1234", tags);

        assertThat(decision.classification())
                .isEqualTo(new RouterClassification(
                        NltiIntentType.QUERY, NltiRiskLevel.LOW, RequestComplexity.SINGLE_LOOKUP, "workorder"));
        assertThat(decision.tier()).isEqualTo(ModelTier.T2_SIMPLE);
    }

    @Test
    @DisplayName("a field the model did not decide takes safeDefault()'s value for that field alone")
    void missingFieldsTakeTheSafeDefaultPerField() {
        QuestionTags tags = acting(Map.of(TagName.INTENT, "QUERY", TagName.DOMAIN, "workorder"));

        RouterClassification classification = router.classify("anything", tags).classification();

        assertThat(classification.intentType()).isEqualTo(NltiIntentType.QUERY);
        assertThat(classification.domain()).isEqualTo("workorder");
        assertThat(classification.riskLevel())
                .isEqualTo(RouterClassification.safeDefault().riskLevel());
        assertThat(classification.complexity())
                .isEqualTo(RouterClassification.safeDefault().complexity());
    }

    @Test
    @DisplayName("§3.5 risk never downgrades: a risk that did not act is HIGH and selects T2_COMPLEX")
    void lowConfidenceRiskIsHighAndRoutesComplex() {
        QuestionTags tags = acting(
                Map.of(TagName.INTENT, "QUERY", TagName.COMPLEXITY, "SINGLE_LOOKUP", TagName.DOMAIN, "workorder"));

        NltiRouter.RoutingDecision decision = router.classify("anything", tags);

        assertThat(decision.classification().riskLevel()).isEqualTo(NltiRiskLevel.HIGH);
        assertThat(decision.tier()).isEqualTo(ModelTier.T2_COMPLEX);
    }

    @Test
    @DisplayName("heuristic router answers are safeDefault(): shadow and off route to T2_COMPLEX")
    void heuristicRecordRoutesToTheSafeDefault() {
        Map<String, TagAnswer> heuristic = new LinkedHashMap<>();
        RouterClassification safe = RouterClassification.safeDefault();
        heuristic.put(
                TagName.INTENT.wireName(), TagAnswer.heuristic(safe.intentType().name()));
        heuristic.put(
                TagName.RISK.wireName(), TagAnswer.heuristic(safe.riskLevel().name()));
        heuristic.put(
                TagName.COMPLEXITY.wireName(),
                TagAnswer.heuristic(safe.complexity().name()));
        heuristic.put(TagName.DOMAIN.wireName(), TagAnswer.heuristic(safe.domain()));

        NltiRouter.RoutingDecision decision = router.classify("anything", QuestionTags.heuristic(heuristic));

        assertThat(decision.classification()).isEqualTo(safe);
        assertThat(decision.tier()).isEqualTo(ModelTier.T2_COMPLEX);
    }

    @Test
    @DisplayName("no record at all: the safe default and T2_COMPLEX")
    void noneRoutesToTheSafeDefault() {
        assertThat(router.classify("anything").classification()).isEqualTo(RouterClassification.safeDefault());
        assertThat(router.route("anything")).isEqualTo(ModelTier.T2_COMPLEX);
    }

    @Test
    @DisplayName("an unknown label is not a value: the field takes the safe default")
    void unknownLabelTakesTheSafeDefault() {
        QuestionTags tags = acting(Map.of(TagName.INTENT, "WAT", TagName.RISK, "LOW"));

        RouterClassification classification = router.classify("anything", tags).classification();

        assertThat(classification.intentType()).isEqualTo(NltiIntentType.UNKNOWN);
        assertThat(classification.riskLevel()).isEqualTo(NltiRiskLevel.LOW);
    }
}
