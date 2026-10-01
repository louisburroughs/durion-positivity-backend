package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.config.TaggingProperties;
import com.positivity.mcp.internal.domain.FallbackReason;
import com.positivity.mcp.internal.domain.QuestionTagger;
import com.positivity.mcp.internal.domain.QuestionTags;
import com.positivity.mcp.internal.domain.RouterClassification;
import com.positivity.mcp.internal.domain.TagAnswer;
import com.positivity.mcp.internal.domain.TagName;
import com.positivity.mcp.internal.domain.TagSource;
import com.positivity.mcp.internal.domain.TaggingMode;
import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.enums.NltiIntentType;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import com.positivity.mcp.internal.scopegraph.LexiconLookup;
import com.positivity.mcp.internal.scopegraph.ScopeConsumers;
import com.positivity.mcp.internal.scopegraph.ScopeResolverFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * ADR-0068 §3, §6 / spec §2.1, §2.5, §2.6, §4: the {@code enforce} merge in {@link TaggingService}:
 * each tag alone, thresholds and {@code low_confidence}, the {@code :veto} direction, the admin tag's
 * fixed veto, the workflow-state rules and the lexicon lookup gated on an {@code ACTION} intent.
 */
class TaggingEnforceMergeTest {

    private static final String PO_MESSAGE = "create po for 40 tires from Michelin";
    private static final String STOCK_MESSAGE = "is sku 4411 in stock at the downtown store?";
    private static final String ADMIN_MESSAGE = "list all users";
    private static final String PLAIN_MESSAGE = "tell me about the shop";

    private final HeuristicQuestionTagger heuristic = HeuristicQuestionTagger.withDefaultCatalog();

    /** A model answering a fixed set of tags with a fixed confidence; every other tag is unanswered. */
    private static QuestionTagger model(double confidence, Map<String, String> values) {
        return message -> {
            Map<String, TagAnswer> answers = new LinkedHashMap<>();
            values.forEach((name, value) -> {
                if (TagName.isEntityQuestion(name) || isNoul(name)) {
                    boolean truth = Boolean.parseBoolean(value);
                    double probability = truth ? confidence : 1.0 - confidence;
                    answers.put(name, TagAnswer.noul(probability));
                } else {
                    answers.put(name, new TagAnswer(value, confidence, TagSource.JEV));
                }
            });
            return new QuestionTags(TaggingMode.ENFORCE, Map.of(), answers, answers, null, "stub", 5L, false);
        };
    }

    private static boolean isNoul(String name) {
        return TagName.fromWireName(name)
                .map(tag -> tag.primitive() == TagName.Primitive.NOUL)
                .orElse(false);
    }

    private static TaggingProperties enforce(List<String> enforced, Map<String, Double> thresholds) {
        return new TaggingProperties(TaggingMode.ENFORCE, enforced, null, thresholds, 0);
    }

    private TaggingService service(TaggingProperties properties, QuestionTagger model) {
        return new TaggingService(properties, heuristic, model, new SimpleMeterRegistry());
    }

    private TaggingService service(HeuristicQuestionTagger tagger, TaggingProperties properties, QuestionTagger model) {
        return new TaggingService(properties, tagger, model, new SimpleMeterRegistry());
    }

    @Nested
    @DisplayName("each Noul tag alone in enforced-tags")
    class EachTagAlone {

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = TagName.class,
                names = {
                    "FOLLOWS_PREVIOUS_TURN",
                    "SIMPLE_CHAT",
                    "NEEDS_WEB_SEARCH",
                    "ABOUT_INVENTORY",
                    "ABOUT_ORDERS",
                    "IMPLIES_DATE_WINDOW",
                    "COMPOUND_QUESTION"
                })
        @DisplayName("only the listed tag takes the model's answer; every other tag stays heuristic")
        void onlyTheListedTagActs(TagName listed) {
            QuestionTags heuristicRecord = heuristic.tag(PLAIN_MESSAGE);
            Map<String, String> flipped = new LinkedHashMap<>();
            heuristicRecord.heuristic().forEach((name, answer) -> {
                if (isNoul(name)) {
                    flipped.put(name, Boolean.toString(!answer.isTrue()));
                }
            });
            TaggingService service = service(enforce(List.of(listed.wireName()), Map.of()), model(0.9, flipped));

            QuestionTags tags = service.tag(PLAIN_MESSAGE);

            assertThat(tags.enforced(listed)).isTrue();
            assertThat(tags.is(listed)).isNotEqualTo(heuristicRecord.is(listed));
            for (TagName other : TagName.values()) {
                if (other != listed && other != TagName.ENTITY) {
                    assertThat(tags.enforced(other)).as(other.name()).isFalse();
                    assertThat(tags.acting().get(other.wireName()))
                            .as(other.name())
                            .isEqualTo(heuristicRecord.heuristic().get(other.wireName()));
                }
            }
            assertThat(tags.tagFallbackReasons()).isEmpty();
        }
    }

    @Test
    @DisplayName("below threshold: the heuristic acts and low_confidence is recorded for that tag alone")
    void belowThresholdFallsBackPerTag() {
        TaggingService service = service(
                enforce(List.of("needs_web_search", "about_orders"), Map.of("needs_web_search", 0.9)),
                model(0.8, Map.of("needs_web_search", "true", "about_orders", "true")));

        QuestionTags tags = service.tag(PLAIN_MESSAGE);

        assertThat(tags.needsWebSearch()).isFalse();
        assertThat(tags.enforced(TagName.NEEDS_WEB_SEARCH)).isFalse();
        assertThat(tags.aboutOrders()).isTrue();
        assertThat(tags.enforced(TagName.ABOUT_ORDERS)).isTrue();
        assertThat(tags.tagFallbackReasons())
                .containsExactly(Map.entry("needs_web_search", FallbackReason.LOW_CONFIDENCE));
        assertThat(tags.fallbackReason())
                .as("the turn itself did not fall back")
                .isNull();
    }

    @Test
    @DisplayName("an unlisted tag the model answered is recorded but never acts, and is not a fallback")
    void unlistedTagNeverActs() {
        TaggingService service =
                service(enforce(List.of("about_orders"), Map.of()), model(0.99, Map.of("needs_web_search", "true")));

        QuestionTags tags = service.tag(PLAIN_MESSAGE);

        assertThat(tags.model()).containsKey("needs_web_search");
        assertThat(tags.needsWebSearch()).isFalse();
        assertThat(tags.tagFallbackReasons()).isEmpty();
    }

    @Nested
    @DisplayName("spec §2.1: the :veto direction")
    class VetoDirection {

        @Test
        @DisplayName("veto: a model false at or above threshold turns a heuristic true into false")
        void vetoTurnsTrueIntoFalse() {
            assertThat(heuristic.tag(STOCK_MESSAGE).aboutInventory()).isTrue();
            TaggingService service = service(
                    enforce(List.of("about_inventory:veto"), Map.of()), model(0.9, Map.of("about_inventory", "false")));

            QuestionTags tags = service.tag(STOCK_MESSAGE);

            assertThat(tags.aboutInventory()).isFalse();
            assertThat(tags.enforced(TagName.ABOUT_INVENTORY)).isTrue();
        }

        @Test
        @DisplayName("veto: a model true never acts over a heuristic false")
        void vetoNeverTurnsFalseIntoTrue() {
            assertThat(heuristic.tag(PLAIN_MESSAGE).aboutInventory()).isFalse();
            TaggingService service = service(
                    enforce(List.of("about_inventory:veto"), Map.of()), model(0.99, Map.of("about_inventory", "true")));

            QuestionTags tags = service.tag(PLAIN_MESSAGE);

            assertThat(tags.aboutInventory()).isFalse();
            assertThat(tags.enforced(TagName.ABOUT_INVENTORY)).isFalse();
            assertThat(tags.tagFallbackReasons())
                    .as("the direction, not the confidence, kept the heuristic")
                    .isEmpty();
        }

        @Test
        @DisplayName("symmetric: the same model true does act over a heuristic false")
        void symmetricTurnsFalseIntoTrue() {
            TaggingService service = service(
                    enforce(List.of("about_inventory"), Map.of()), model(0.99, Map.of("about_inventory", "true")));

            assertThat(service.tag(PLAIN_MESSAGE).aboutInventory()).isTrue();
        }

        @Test
        @DisplayName("veto on a model-only tag: entity:veto never seeds, however confident the model's true")
        void vetoNeverActsOnAModelOnlyTag() {
            String workorder = TagName.entityWireName("work-order");
            TaggingService veto =
                    service(enforce(List.of("entity:veto"), Map.of()), model(0.99, Map.of(workorder, "true")));
            TaggingService symmetric =
                    service(enforce(List.of("entity"), Map.of()), model(0.99, Map.of(workorder, "true")));

            QuestionTags tags = veto.tag(PLAIN_MESSAGE);

            assertThat(tags.model()).containsKey(workorder);
            assertThat(tags.acting()).doesNotContainKey(workorder);
            assertThat(tags.entitySeeds()).isEmpty();
            assertThat(tags.tagSeeds().isEmpty()).isTrue();
            assertThat(tags.tagFallbackReasons())
                    .as("the direction, not the confidence, kept it from acting")
                    .isEmpty();
            assertThat(symmetric.tag(PLAIN_MESSAGE).entitySeeds())
                    .as("the same answer under a symmetric entry does seed")
                    .containsExactly("work-order");
        }

        @Test
        @DisplayName("veto below threshold: the heuristic true stands and low_confidence is recorded")
        void vetoBelowThresholdKeepsTheHeuristic() {
            TaggingService service = service(
                    enforce(List.of("about_inventory:veto"), Map.of()), model(0.6, Map.of("about_inventory", "false")));

            QuestionTags tags = service.tag(STOCK_MESSAGE);

            assertThat(tags.aboutInventory()).isTrue();
            assertThat(tags.tagFallbackReasons()).containsKey("about_inventory");
        }
    }

    @Nested
    @DisplayName("ADR-0068 §3.4: admin_account_question is veto-only whatever the list says")
    class AdminVeto {

        @Test
        @DisplayName("listed symmetric, model true, no keyword: the acting value stays false")
        void modelTrueNeverFiresWithoutAKeyword() {
            assertThat(heuristic.tag(PLAIN_MESSAGE).adminAccountQuestion()).isFalse();
            TaggingService service = service(
                    enforce(List.of("admin_account_question"), Map.of()),
                    model(0.99, Map.of("admin_account_question", "true")));

            QuestionTags tags = service.tag(PLAIN_MESSAGE);

            assertThat(tags.adminAccountQuestion()).isFalse();
            assertThat(tags.enforced(TagName.ADMIN_ACCOUNT_QUESTION)).isFalse();
        }

        @Test
        @DisplayName("model false at or above threshold with a keyword match: vetoed")
        void modelFalseVetoesAKeywordMatch() {
            assertThat(heuristic.tag(ADMIN_MESSAGE).adminAccountQuestion()).isTrue();
            TaggingService service = service(
                    enforce(List.of("admin_account_question"), Map.of()),
                    model(0.9, Map.of("admin_account_question", "false")));

            QuestionTags tags = service.tag(ADMIN_MESSAGE);

            assertThat(tags.adminAccountQuestion()).isFalse();
            assertThat(tags.enforced(TagName.ADMIN_ACCOUNT_QUESTION)).isTrue();
        }

        @Test
        @DisplayName("model false below threshold: the keyword match stands")
        void lowConfidenceFalseDoesNotVeto() {
            TaggingService service = service(
                    enforce(List.of("admin_account_question"), Map.of()),
                    model(0.6, Map.of("admin_account_question", "false")));

            assertThat(service.tag(ADMIN_MESSAGE).adminAccountQuestion()).isTrue();
        }
    }

    @Nested
    @DisplayName("spec §2.6: workflow_state in enforce")
    class WorkflowStateRules {

        @Test
        @DisplayName("IDLE at or above the ordinary threshold overrides a phrase-matched CREATING_PO")
        void idleIsAValue() {
            assertThat(heuristic.tag(PO_MESSAGE).workflowState()).isEqualTo(WorkflowState.CREATING_PO);
            TaggingService service = service(
                    enforce(List.of("workflow_state"), Map.of("workflow_state.non-idle", 0.99)),
                    model(0.8, Map.of("workflow_state", "IDLE")));

            QuestionTags tags = service.tag(PO_MESSAGE);

            assertThat(tags.workflowState()).isEqualTo(WorkflowState.IDLE);
            assertThat(tags.enforced(TagName.WORKFLOW_STATE)).isTrue();
        }

        @Test
        @DisplayName("a non-IDLE answer between the tag threshold and non-idle is not acted on")
        void nonIdleBelowTheStrictThresholdFallsBack() {
            TaggingService service = service(
                    enforce(List.of("workflow_state"), Map.of("workflow_state", 0.7, "workflow_state.non-idle", 0.9)),
                    model(0.8, Map.of("workflow_state", "RECEIVING_ASN")));

            QuestionTags tags = service.tag(PLAIN_MESSAGE);

            assertThat(tags.workflowState()).isEqualTo(WorkflowState.IDLE);
            assertThat(tags.enforced(TagName.WORKFLOW_STATE)).isFalse();
            assertThat(tags.tagFallbackReasons()).containsEntry("workflow_state", FallbackReason.LOW_CONFIDENCE);
        }

        @Test
        @DisplayName("a non-IDLE answer at the non-idle threshold acts; non-idle defaults to the tag threshold")
        void nonIdleAtTheStrictThresholdActs() {
            TaggingService strict = service(
                    enforce(List.of("workflow_state"), Map.of("workflow_state", 0.7, "workflow_state.non-idle", 0.9)),
                    model(0.9, Map.of("workflow_state", "RECEIVING_ASN")));
            assertThat(strict.tag(PLAIN_MESSAGE).workflowState()).isEqualTo(WorkflowState.RECEIVING_ASN);

            TaggingService defaulted = service(
                    enforce(List.of("workflow_state"), Map.of()),
                    model(0.8, Map.of("workflow_state", "RECEIVING_ASN")));
            assertThat(defaulted.tag(PLAIN_MESSAGE).workflowState()).isEqualTo(WorkflowState.RECEIVING_ASN);
        }

        @Test
        @DisplayName("PROCESSING_RETURN is model-only: no phrase yields it, the model can")
        void processingReturnIsModelOnly() {
            String message = "the customer is returning the set of tires for a refund";
            assertThat(heuristic.tag(message).workflowState()).isEqualTo(WorkflowState.IDLE);
            TaggingService service = service(
                    enforce(List.of("workflow_state"), Map.of()),
                    model(0.9, Map.of("workflow_state", "PROCESSING_RETURN")));

            assertThat(service.tag(message).workflowState()).isEqualTo(WorkflowState.PROCESSING_RETURN);
        }
    }

    @Nested
    @DisplayName("ADR-0068 §3.3: the lexicon lookup as the heuristic workflow source")
    class LexiconLookupRules {

        private static final String LOOKUP_MESSAGE = "please place a new order for 40 tires with Michelin";

        private HeuristicQuestionTagger taggerWith(ScopeGraphProperties properties) {
            ScopeConsumers consumers = ScopeResolverFixtures.consumers(properties, new SimpleMeterRegistry());
            return new HeuristicQuestionTagger(
                    new SimpleChatClassifier(
                            com.positivity.mcp.internal.classification.SimpleChatRuleDefaults.defaultCatalog()),
                    3,
                    new LexiconLookup(),
                    consumers);
        }

        @Test
        @DisplayName("lookups enforced and acting intent ACTION: the entity's workflow_state acts, with a lexicon rule")
        void lookupActsOnAction() {
            HeuristicQuestionTagger tagger =
                    taggerWith(ScopeResolverFixtures.enforce(60, ScopeGraphProperties.Consumer.LOOKUPS));
            // "order" is the sales order's term; "purchase order" and "po" name purchase-order.
            String message = "create a purchase order for 40 tires from Michelin";
            assertThat(tagger.tag(message).workflowState()).isEqualTo(WorkflowState.CREATING_PO);
            String noPhrase = "I need to buy 40 tires from Michelin, start the PO";
            assertThat(tagger.tag(noPhrase).workflowState())
                    .as("no phrase of deriveWorkflowState matches")
                    .isEqualTo(WorkflowState.IDLE);
            TaggingService service =
                    service(tagger, enforce(List.of("intent"), Map.of()), model(0.9, Map.of("intent", "ACTION")));

            QuestionTags tags = service.tag(noPhrase);

            assertThat(tags.intent()).isEqualTo(NltiIntentType.ACTION);
            assertThat(tags.workflowState()).isEqualTo(WorkflowState.CREATING_PO);
            assertThat(tags.enforced(TagName.WORKFLOW_STATE)).isFalse();
            assertThat(tags.heuristic().get("workflow_state").rule()).isEqualTo("lexicon:purchase-order");
            assertThat(tags.acting().get("workflow_state").rule()).isEqualTo("lexicon:purchase-order");
        }

        @Test
        @DisplayName("an acting entity_<key> seed is a lookup source: an ACTION tagged purchase-order is CREATING_PO")
        void lookupReadsTheActingEntitySeeds() {
            HeuristicQuestionTagger tagger =
                    taggerWith(ScopeResolverFixtures.enforce(60, ScopeGraphProperties.Consumer.LOOKUPS));
            String message = "buy 40 tires from Michelin";
            String purchaseOrder = TagName.entityWireName("purchase-order");
            TaggingService untagged =
                    service(tagger, enforce(List.of("intent"), Map.of()), model(0.9, Map.of("intent", "ACTION")));
            assertThat(untagged.tag(message).workflowState())
                    .as("the wording names no entity with a workflow state and matches no phrase")
                    .isEqualTo(WorkflowState.IDLE);
            TaggingService tagged = service(
                    tagger,
                    enforce(List.of("intent", "entity"), Map.of()),
                    model(0.9, Map.of("intent", "ACTION", purchaseOrder, "true")));

            QuestionTags tags = tagged.tag(message);

            assertThat(tags.entitySeeds()).containsExactly("purchase-order");
            assertThat(tags.workflowState()).isEqualTo(WorkflowState.CREATING_PO);
            assertThat(tags.enforced(TagName.WORKFLOW_STATE)).isFalse();
            assertThat(tags.acting().get("workflow_state").rule()).isEqualTo("lexicon_tag:purchase-order");
        }

        @Test
        @DisplayName("acting intent QUERY: the lookup does not fire; how many POs are open stays IDLE")
        void lookupNeedsAnActionIntent() {
            HeuristicQuestionTagger tagger =
                    taggerWith(ScopeResolverFixtures.enforce(60, ScopeGraphProperties.Consumer.LOOKUPS));
            String message = "how many POs are open with Michelin right now";
            TaggingService query =
                    service(tagger, enforce(List.of("intent"), Map.of()), model(0.9, Map.of("intent", "QUERY")));
            TaggingService unknown =
                    service(tagger, enforce(List.of(), Map.of()), model(0.9, Map.of("intent", "ACTION")));

            assertThat(query.tag(message).workflowState()).isEqualTo(WorkflowState.IDLE);
            assertThat(unknown.tag(message).workflowState())
                    .as("intent not enforced: the acting intent is the heuristic UNKNOWN")
                    .isEqualTo(WorkflowState.IDLE);
        }

        @Test
        @DisplayName("lookups not enforced (shadow, or enforce without it): the phrase match stands")
        void lookupNeedsTheConsumer() {
            for (ScopeGraphProperties properties : List.of(
                    ScopeResolverFixtures.shadow(60),
                    ScopeResolverFixtures.enforce(60, ScopeGraphProperties.Consumer.TOOLS),
                    ScopeGraphProperties.off())) {
                HeuristicQuestionTagger tagger = taggerWith(properties);
                TaggingService service =
                        service(tagger, enforce(List.of("intent"), Map.of()), model(0.9, Map.of("intent", "ACTION")));
                assertThat(service.tag("start the PO for Michelin").workflowState())
                        .as(properties.toString())
                        .isEqualTo(WorkflowState.IDLE);
            }
        }

        @Test
        @DisplayName("the model's workflow_state at or above threshold comes before the lookup")
        void modelAnswerWinsOverTheLookup() {
            HeuristicQuestionTagger tagger =
                    taggerWith(ScopeResolverFixtures.enforce(60, ScopeGraphProperties.Consumer.LOOKUPS));
            TaggingService service = service(
                    tagger,
                    enforce(List.of("intent", "workflow_state"), Map.of()),
                    model(0.9, Map.of("intent", "ACTION", "workflow_state", "IDLE")));

            QuestionTags tags = service.tag("start the PO for Michelin");

            assertThat(tags.workflowState()).isEqualTo(WorkflowState.IDLE);
            assertThat(tags.enforced(TagName.WORKFLOW_STATE)).isTrue();
            assertThat(tags.heuristic().get("workflow_state").rule())
                    .as("the heuristic answer still records what the lookup said")
                    .isEqualTo("lexicon:purchase-order");
        }

        @Test
        @DisplayName("an entity without a workflow_state yields nothing: the phrase match stands")
        void entityWithoutAStateFallsToThePhrase() {
            HeuristicQuestionTagger tagger =
                    taggerWith(ScopeResolverFixtures.enforce(60, ScopeGraphProperties.Consumer.LOOKUPS));
            TaggingService service =
                    service(tagger, enforce(List.of("intent"), Map.of()), model(0.9, Map.of("intent", "ACTION")));

            assertThat(service.tag("create a work order for the Civic").workflowState())
                    .isEqualTo(WorkflowState.IDLE);
            assertThat(service.tag("cycle count for aisle 4").workflowState()).isEqualTo(WorkflowState.INVENTORY_RECON);
        }
    }

    @Nested
    @DisplayName("router tags and entity Nouls")
    class RouterAndEntities {

        @Test
        @DisplayName("router tags act per field; the unlisted ones keep safeDefault()")
        void routerTagsPerField() {
            TaggingService service = service(
                    enforce(List.of("intent", "risk", "domain"), Map.of()),
                    model(
                            0.9,
                            Map.of(
                                    "intent",
                                    "QUERY",
                                    "risk",
                                    "LOW",
                                    "complexity",
                                    "SINGLE_LOOKUP",
                                    "domain",
                                    "billing")));

            RouterClassification classification = service.tag(PLAIN_MESSAGE).routerClassification();

            assertThat(classification.intentType()).isEqualTo(NltiIntentType.QUERY);
            assertThat(classification.riskLevel()).isEqualTo(NltiRiskLevel.LOW);
            assertThat(classification.complexity())
                    .isEqualTo(RouterClassification.safeDefault().complexity());
            assertThat(classification.domain()).isEqualTo("billing");
        }

        @Test
        @DisplayName(
                "entity Nouls: true at or above threshold seeds, below threshold is low_confidence, unlisted never")
        void entityNouls() {
            Map<String, String> answers = Map.of(
                    TagName.entityWireName("work-order"), "true",
                    TagName.entityWireName("invoice"), "false");
            TaggingService listed =
                    service(enforce(List.of("entity"), Map.of("entity.invoice", 0.99)), model(0.9, answers));
            QuestionTags tags = listed.tag(PLAIN_MESSAGE);
            assertThat(tags.entitySeeds()).containsExactly("work-order");
            assertThat(tags.tagFallbackReasons()).containsOnlyKeys(TagName.entityWireName("invoice"));
            assertThat(tags.tagSeeds().entityKeys()).containsExactly("work-order");
            assertThat(tags.tagSeeds().ragScope()).isNull();

            TaggingService unlisted = service(enforce(List.of("domain"), Map.of()), model(0.9, answers));
            assertThat(unlisted.tag(PLAIN_MESSAGE).entitySeeds()).isEmpty();
        }

        @Test
        @DisplayName("tagSeeds: an acting domain other than master seeds its RAG scope; master seeds nothing")
        void domainSeeds() {
            TaggingService billing =
                    service(enforce(List.of("domain"), Map.of()), model(0.9, Map.of("domain", "billing")));
            assertThat(billing.tag(PLAIN_MESSAGE).tagSeeds().ragScope()).isEqualTo("billing");

            TaggingService master =
                    service(enforce(List.of("domain"), Map.of()), model(0.9, Map.of("domain", "master")));
            assertThat(master.tag(PLAIN_MESSAGE).tagSeeds().isEmpty()).isTrue();

            TaggingService shadow = new TaggingService(
                    new TaggingProperties(TaggingMode.SHADOW, List.of(), null, Map.of(), 0),
                    heuristic,
                    model(0.9, Map.of("domain", "billing", TagName.entityWireName("invoice"), "true")),
                    new SimpleMeterRegistry());
            assertThat(shadow.tag(PLAIN_MESSAGE).tagSeeds().isEmpty()).isTrue();
        }
    }

    @Test
    @DisplayName("TaggingProperties: directions and the non-idle threshold")
    void propertiesParseDirections() {
        TaggingProperties properties = new TaggingProperties(
                TaggingMode.ENFORCE,
                List.of("simple_chat:veto", "about_orders", "Admin_Account_Question"),
                null,
                Map.of("workflow_state", 0.7, "workflow_state.non-idle", 0.9, "entity", 0.8),
                0);

        assertThat(properties.enforces(TagName.SIMPLE_CHAT)).isTrue();
        assertThat(properties.directionOf(TagName.SIMPLE_CHAT)).isEqualTo(TaggingProperties.Direction.VETO);
        assertThat(properties.directionOf(TagName.ABOUT_ORDERS)).isEqualTo(TaggingProperties.Direction.SYMMETRIC);
        assertThat(properties.directionOf(TagName.ADMIN_ACCOUNT_QUESTION)).isEqualTo(TaggingProperties.Direction.VETO);
        assertThat(properties.enforces(TagName.ADMIN_ACCOUNT_QUESTION)).isTrue();
        assertThat(properties.enforces(TagName.RISK)).isFalse();
        assertThat(properties.enforces(TagName.entityWireName("invoice"))).isFalse();
        assertThat(properties.nonIdleThreshold()).isEqualTo(0.9);
        assertThat(properties.thresholdFor(TagName.WORKFLOW_STATE)).isEqualTo(0.7);
        assertThat(new TaggingProperties(TaggingMode.ENFORCE, List.of(), null, Map.of("workflow_state", 0.6), 0)
                        .nonIdleThreshold())
                .as("non-idle defaults to the tag's threshold")
                .isEqualTo(0.6);
        assertThat(Set.copyOf(properties.enforcedTags()))
                .containsExactlyInAnyOrder("simple_chat:veto", "about_orders", "admin_account_question");
    }
}
