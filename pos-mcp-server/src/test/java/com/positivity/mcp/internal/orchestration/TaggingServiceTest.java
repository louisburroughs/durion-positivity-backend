package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.client.JevProviderException;
import com.positivity.mcp.internal.config.TaggingProperties;
import com.positivity.mcp.internal.domain.FallbackReason;
import com.positivity.mcp.internal.domain.QuestionTagger;
import com.positivity.mcp.internal.domain.QuestionTags;
import com.positivity.mcp.internal.domain.RequestComplexity;
import com.positivity.mcp.internal.domain.RouterClassification;
import com.positivity.mcp.internal.domain.TagAnswer;
import com.positivity.mcp.internal.domain.TagName;
import com.positivity.mcp.internal.domain.TagSource;
import com.positivity.mcp.internal.domain.TaggingMode;
import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.enums.NltiIntentType;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** ADR-0068 §2, §6 / spec §2.5, §4: mode logic, merge, fallback reasons and meters. */
class TaggingServiceTest {

    /** Exact simple-chat catalog hits: the T0 skip never calls the provider for these (spec §2.5). */
    private static final List<String> CERTAIN_T0 = List.of("hello", "merci", "buenos días");

    /** Simple chat by the caps and the absence of a task signal, not by an exact rule: still tagged. */
    private static final String UNCERTAIN_T0 = "merci beaucoup";

    private static final List<String> MESSAGES = List.of(
            UNCERTAIN_T0,
            "now rank those same ten by outstanding balance instead",
            "create po for 40 tires from Michelin",
            "we are receiving asn 5521 today",
            "cycle count for aisle 4",
            "is sku 4411 in stock at the downtown store?",
            "what's the latest news on tire tariffs?",
            "revenue last month",
            "list all users",
            "who has access to the receivables ledger",
            "show me open work orders. also, which technicians are free today?");

    private final HeuristicQuestionTagger heuristic = HeuristicQuestionTagger.withDefaultCatalog();

    /** A model that contradicts the heuristic on every tag it answers, and seeds an entity. */
    private final class OppositeTagger implements QuestionTagger {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public QuestionTags tag(String message) {
            calls.incrementAndGet();
            QuestionTags h = heuristic.tag(message);
            Map<String, TagAnswer> answers = new LinkedHashMap<>();
            h.heuristic().forEach((name, answer) -> answers.put(name, opposite(name, answer)));
            answers.put(TagName.entityWireName("workorder"), TagAnswer.noul(0.9));
            return new QuestionTags(
                    TaggingMode.SHADOW,
                    Map.of(),
                    answers,
                    answers,
                    null,
                    "stub-model",
                    42L,
                    true,
                    46,
                    18_432,
                    "abc123");
        }

        private TagAnswer opposite(String name, TagAnswer answer) {
            TagName tag = TagName.fromWireName(name).orElseThrow();
            return switch (tag) {
                case WORKFLOW_STATE ->
                    new TagAnswer(answer.value().equals("IDLE") ? "PROCESSING_RETURN" : "IDLE", 0.99, TagSource.JEV);
                case INTENT -> new TagAnswer(NltiIntentType.ACTION.name(), 0.99, TagSource.JEV);
                case COMPLEXITY -> new TagAnswer(RequestComplexity.SINGLE_LOOKUP.name(), 0.99, TagSource.JEV);
                case RISK -> new TagAnswer(NltiRiskLevel.LOW.name(), 0.99, TagSource.JEV, 0.1);
                case DOMAIN -> new TagAnswer("accounting", 0.99, TagSource.JEV);
                default -> TagAnswer.noul(answer.isTrue() ? 0.01 : 0.99);
            };
        }
    }

    private static TaggingProperties mode(TaggingMode mode) {
        return new TaggingProperties(mode, List.of(), null, Map.of(), 0);
    }

    @Test
    @DisplayName("mode off: the heuristic record alone, no provider call, no meter registered")
    void offNeverCallsTheProvider() {
        OppositeTagger model = new OppositeTagger();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        TaggingService service = new TaggingService(mode(TaggingMode.OFF), heuristic, model, meters);

        QuestionTags tags = service.tag("create po for 40 tires from Michelin");

        assertThat(model.calls).hasValue(0);
        assertThat(tags.mode()).isEqualTo(TaggingMode.OFF);
        assertThat(tags.model()).isEmpty();
        assertThat(tags.acting()).isEqualTo(tags.heuristic());
        assertThat(tags.workflowState()).isEqualTo(WorkflowState.CREATING_PO);
        assertThat(tags.providerModel()).isNull();
        assertThat(tags.fallbackReason()).isNull();
        assertThat(meters.getMeters()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"SHADOW", "ENFORCE"})
    @DisplayName(
            "shadow, and enforce with an empty list, == off on every decision, with a model that contradicts every tag")
    void shadowEqualsOffOnEveryDecision(String modeName) {
        TaggingMode mode = TaggingMode.valueOf(modeName);
        OppositeTagger model = new OppositeTagger();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        // Spec §2.1: mode enforce with nothing listed behaves as shadow.
        TaggingProperties properties = new TaggingProperties(mode, List.of(), null, Map.of(), 0);
        TaggingService off = new TaggingService(mode(TaggingMode.OFF), heuristic, null, meters);
        TaggingService on = new TaggingService(properties, heuristic, model, meters);

        for (String message : MESSAGES) {
            QuestionTags expected = off.tag(message);
            QuestionTags actual = on.tag(message);
            assertThat(actual.acting()).as("acting answers for \"%s\"", message).isEqualTo(expected.acting());
            assertThat(actual.simpleChat()).isEqualTo(expected.simpleChat());
            assertThat(actual.followsPreviousTurn()).isEqualTo(expected.followsPreviousTurn());
            assertThat(actual.workflowState()).isEqualTo(expected.workflowState());
            assertThat(actual.needsWebSearch()).isEqualTo(expected.needsWebSearch());
            assertThat(actual.aboutInventory()).isEqualTo(expected.aboutInventory());
            assertThat(actual.aboutOrders()).isEqualTo(expected.aboutOrders());
            assertThat(actual.impliesDateWindow()).isEqualTo(expected.impliesDateWindow());
            assertThat(actual.adminAccountQuestion()).isEqualTo(expected.adminAccountQuestion());
            assertThat(actual.compoundQuestion()).isEqualTo(expected.compoundQuestion());
            assertThat(actual.routerClassification()).isEqualTo(RouterClassification.safeDefault());
            assertThat(actual.entitySeeds())
                    .as("no entity seed acts outside enforce")
                    .isEmpty();
            assertThat(actual.tagFallbackReasons()).isEmpty();
            // ... while everything the model said is recorded beside it.
            assertThat(actual.mode()).isEqualTo(mode);
            assertThat(actual.model()).isNotEmpty();
            assertThat(actual.model()).containsKey(TagName.entityWireName("workorder"));
            // ADR-0068 §3.6: the model the tagger reports ("stub-model") is never used; the configured one is.
            assertThat(actual.providerModel()).isEqualTo(TaggingProperties.Provider.DEFAULT_MODEL);
            assertThat(actual.latencyMs()).isEqualTo(42L);
            assertThat(actual.stateTruncated()).isTrue();
            assertThat(actual.questionCount()).isEqualTo(46);
            assertThat(actual.requestBodyBytes()).isEqualTo(18_432);
            assertThat(actual.optionListHash()).isEqualTo("abc123");
            assertThat(actual.fallbackReason()).isNull();
            assertThat(actual.agreementRate()).hasValue(0.0);
        }
        assertThat(model.calls).hasValue(MESSAGES.size());
        assertThat(meters.get(TaggingService.REQUESTS)
                        .tags("model", TaggingProperties.Provider.DEFAULT_MODEL, "outcome", "ok")
                        .counter()
                        .count())
                .isEqualTo(MESSAGES.size());
        assertThat(meters.find(TaggingService.REQUESTS)
                        .tags("model", "stub-model")
                        .counter())
                .isNull();
        assertThat(meters.find(TaggingService.LATENCY)
                        .tags("model", "stub-model")
                        .timer())
                .isNull();
        assertThat(meters.get(TaggingService.STATE_TRUNCATED).counter().count()).isEqualTo(MESSAGES.size());
        assertThat(meters.get(TaggingService.AGREEMENT)
                        .tags("tag", "simple_chat", "agree", "false")
                        .counter()
                        .count())
                .isEqualTo(MESSAGES.size());
        assertThat(meters.find(TaggingService.AGREEMENT).tags("agree", "true").counter())
                .isNull();
        assertThat(meters.find(TaggingService.AGREEMENT).tags("tag", "entity_1").counter())
                .isNull();
        assertThat(meters.find(TaggingService.FALLBACK).counter()).isNull();
        assertThat(meters.get(TaggingService.LATENCY)
                        .tags("model", TaggingProperties.Provider.DEFAULT_MODEL)
                        .timer()
                        .count())
                .isEqualTo(MESSAGES.size());
    }

    @Test
    @DisplayName("ADR-0068 §3.6: the configured model names the record and the meters, never the provider's string")
    void providerReportedModelIsNeverUsed() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        TaggingProperties properties =
                TaggingProperties.shadow(new TaggingProperties.Provider(null, "configured-model", null, null, null));
        QuestionTagger reportsAnotherModel = message -> new QuestionTags(
                TaggingMode.SHADOW,
                Map.of(),
                Map.of(TagName.SIMPLE_CHAT.wireName(), TagAnswer.noul(0.9)),
                Map.of(TagName.SIMPLE_CHAT.wireName(), TagAnswer.noul(0.9)),
                null,
                "provider says {} %s\nmodel",
                7L,
                false);
        TaggingService service = new TaggingService(properties, heuristic, reportsAnotherModel, meters);

        // Not an exact simple-chat catalog hit: the T0 skip (spec §2.5) must not pre-empt the call.
        QuestionTags tags = service.tag("list all users");

        assertThat(tags.providerModel()).isEqualTo("configured-model");
        assertThat(meters.get(TaggingService.REQUESTS)
                        .tags("model", "configured-model", "outcome", "ok")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(meters.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .noneMatch(tag -> tag.getValue().contains("provider says")));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"TIMEOUT", "ERROR"})
    @DisplayName("a provider failure carries the real truncation flag of the message it was asked about")
    void fallbackCarriesTheRealTruncationFlag(String reasonName) {
        FallbackReason reason = FallbackReason.valueOf(reasonName);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        TaggingProperties properties = new TaggingProperties(TaggingMode.SHADOW, List.of(), null, Map.of(), 20);
        QuestionTagger failing = message -> {
            throw new JevProviderException(reason, "stub failure", null, null);
        };
        TaggingService service = new TaggingService(properties, heuristic, failing, meters);

        QuestionTags cut = service.tag("show me open workorders for every technician at the downtown store");
        QuestionTags whole = service.tag("list all users");

        assertThat(cut.fallbackReason()).isEqualTo(reason);
        assertThat(cut.stateTruncated()).isTrue();
        assertThat(whole.fallbackReason()).isEqualTo(reason);
        assertThat(whole.stateTruncated()).isFalse();
        assertThat(meters.get(TaggingService.STATE_TRUNCATED).counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("spec §2.5 T0 skip: an exact catalog hit makes no provider call and records heuristic_certain")
    void exactCatalogHitSkipsTheProvider() {
        OppositeTagger model = new OppositeTagger();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        TaggingProperties enforce =
                new TaggingProperties(TaggingMode.ENFORCE, List.of("simple_chat"), null, Map.of(), 0);
        TaggingService service = new TaggingService(enforce, heuristic, model, meters);

        for (String message : CERTAIN_T0) {
            QuestionTags tags = service.tag(message);
            assertThat(tags.simpleChat()).as(message).isTrue();
            assertThat(tags.enforced(TagName.SIMPLE_CHAT)).isFalse();
            assertThat(tags.model()).isEmpty();
            assertThat(tags.fallbackReason()).isEqualTo(FallbackReason.HEURISTIC_CERTAIN);
            assertThat(tags.latencyMs()).isZero();
            assertThat(tags.providerModel()).isNull();
            assertThat(tags.mode()).isEqualTo(TaggingMode.ENFORCE);
            assertThat(tags.acting()).isEqualTo(tags.heuristic());
        }
        assertThat(model.calls).hasValue(0);
        assertThat(meters.get(TaggingService.SKIPPED)
                        .tags("reason", "heuristic_certain")
                        .counter()
                        .count())
                .isEqualTo(CERTAIN_T0.size());
        assertThat(meters.find(TaggingService.REQUESTS).counter()).isNull();
        assertThat(meters.find(TaggingService.FALLBACK).counter()).isNull();

        // A simple-chat message that is not an exact rule still goes to the provider.
        assertThat(heuristic.tag(UNCERTAIN_T0).simpleChat()).isTrue();
        service.tag(UNCERTAIN_T0);
        assertThat(model.calls).hasValue(1);
    }

    @Test
    @DisplayName("heuristicOnly never calls the provider whatever the mode")
    void heuristicOnlyNeverCallsTheProvider() {
        OppositeTagger model = new OppositeTagger();
        TaggingService service =
                new TaggingService(mode(TaggingMode.SHADOW), heuristic, model, new SimpleMeterRegistry());

        QuestionTags tags = service.heuristicOnly("hello");

        assertThat(model.calls).hasValue(0);
        assertThat(tags.simpleChat()).isTrue();
        assertThat(tags.model()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"TIMEOUT", "RATE_LIMITED", "ERROR", "MALFORMED"})
    @DisplayName("a provider failure yields the heuristic record with its reason, counted under fallback and requests")
    void providerFailureFallsBackWithReason(String reasonName) {
        FallbackReason reason = FallbackReason.valueOf(reasonName);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        QuestionTagger failing = message -> {
            throw new JevProviderException(reason, "stub failure", 503, null);
        };
        TaggingService service = new TaggingService(mode(TaggingMode.SHADOW), heuristic, failing, meters);

        QuestionTags tags = service.tag("list all users");

        assertThat(tags.acting()).isEqualTo(heuristic.tag("list all users").acting());
        assertThat(tags.adminAccountQuestion()).isTrue();
        assertThat(tags.model()).isEmpty();
        assertThat(tags.fallbackReason()).isEqualTo(reason);
        assertThat(tags.mode()).isEqualTo(TaggingMode.SHADOW);
        assertThat(tags.providerModel()).isEqualTo(TaggingProperties.Provider.DEFAULT_MODEL);
        assertThat(tags.latencyMs()).isNotNull();
        assertThat(tags.agreementRate()).isEmpty();
        assertThat(meters.get(TaggingService.FALLBACK)
                        .tags("reason", reason.wireName())
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(meters.get(TaggingService.REQUESTS)
                        .tags("model", TaggingProperties.Provider.DEFAULT_MODEL, "outcome", reason.wireName())
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("an unexpected exception from the tagger is an ERROR fallback: the turn never fails")
    void unexpectedExceptionIsAnErrorFallback() {
        QuestionTagger broken = message -> {
            throw new IllegalStateException("boom");
        };
        TaggingService service =
                new TaggingService(mode(TaggingMode.SHADOW), heuristic, broken, new SimpleMeterRegistry());

        QuestionTags tags = service.tag("list all users");

        assertThat(tags.fallbackReason()).isEqualTo(FallbackReason.ERROR);
        assertThat(tags.adminAccountQuestion()).isTrue();
    }

    @Test
    @DisplayName("mode shadow without a provider bean behaves as off")
    void shadowWithoutAProviderIsOff() {
        TaggingService service =
                new TaggingService(mode(TaggingMode.SHADOW), heuristic, null, new SimpleMeterRegistry());

        QuestionTags tags = service.tag("hello");

        assertThat(tags.mode()).isEqualTo(TaggingMode.OFF);
        assertThat(tags.model()).isEmpty();
    }
}
