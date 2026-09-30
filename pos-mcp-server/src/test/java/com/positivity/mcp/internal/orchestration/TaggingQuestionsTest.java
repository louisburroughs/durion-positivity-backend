package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.client.JevClient;
import com.positivity.mcp.internal.config.StaticRagPreloadProperties;
import com.positivity.mcp.internal.config.StaticRagPreloadProperties.StaticDocEntry;
import com.positivity.mcp.internal.config.TaggingProperties;
import com.positivity.mcp.internal.domain.TagName;
import com.positivity.mcp.internal.domain.TagQuestion;
import com.positivity.mcp.internal.scopegraph.EntityLexicon;
import com.positivity.mcp.internal.scopegraph.EntityLexiconLoader;
import com.positivity.mcp.internal.scopegraph.ScopeGraphRealConfigValidationTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** ADR-0068 spec §2.2, §2.3, §2.4 (as revised by the NLTI review): the question set and its limits. */
class TaggingQuestionsTest {

    private static final EntityLexicon LEXICON = EntityLexiconLoader.loadDefault();

    /** The tool-catalog domains that have no RAG scope and must never be a {@code domain} option. */
    private static final Set<String> UNMAPPED_TOOL_DOMAINS = Set.of(
            "vehicle-inventory",
            "people-contact",
            "supplier",
            "marketing",
            "location",
            "catalog",
            "vehicle-fitment",
            "shop-manager",
            "price",
            "people",
            "security-service",
            "bulk-loader",
            "mcp-server",
            "date-window");

    private static StaticRagPreloadProperties preload(String profile) {
        return new StaticRagPreloadProperties(ScopeGraphRealConfigValidationTest.ragDocs(profile));
    }

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"default", "alpha"})
    @DisplayName(
            "spec §2.2: the set built from the real lexicon and preload list stays within 64 questions and 2-26 options")
    void realConfigurationStaysWithinTheProviderLimits(String profile) throws Exception {
        TaggingQuestions.QuestionSet set = TaggingQuestions.build(LEXICON, preload(profile));
        List<TagQuestion> questions = set.questions();

        // 12 fixed tags + domain + one Noul per lexicon entity (31 today): 44, well under 64.
        assertThat(questions).hasSize(12 + 1 + LEXICON.entities().size());
        assertThat(questions).hasSizeBetween(44, 46);
        assertThat(questions).hasSizeLessThanOrEqualTo(TaggingQuestions.MAX_QUESTIONS);
        assertThat(questions.stream().map(TagQuestion::wireName).toList()).doesNotHaveDuplicates();
        for (TagQuestion question : questions) {
            assertThat(question.instructions())
                    .as("%s carries instructions", question.wireName())
                    .startsWith(TaggingQuestions.CONTEXT);
            if (question.primitive() != TagName.Primitive.NOUL) {
                assertThat(question.options())
                        .as("%s options", question.wireName())
                        .hasSizeBetween(2, TaggingQuestions.MAX_OPTIONS);
            }
        }
        // The wide request's cost, as the provider would receive it: 64 KiB body cap (spec §2.2).
        byte[] body = new ObjectMapper()
                .writeValueAsBytes(JevClient.requestBody(
                        TaggingProperties.Provider.defaults(),
                        "which customers haven't bought in the last 90 days but spent over $10,000 in the prior year?",
                        questions));
        assertThat(body.length).isLessThan(64 * 1024);
        System.out.printf(
                "tagging questions profile=%s entity-questions=true count=%d bodyBytes=%d optionListHash=%s%n",
                profile, questions.size(), body.length, set.optionListHash());
    }

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"default", "alpha"})
    @DisplayName(
            "the domain options are the preload rag-scopes plus master, described by the lexicon, never tool domains")
    void domainOptionsAreTheRagScopeVocabulary(String profile) {
        TagQuestion domain = TaggingQuestions.domainQuestion(LEXICON, preload(profile));

        Set<String> scopes = new java.util.TreeSet<>(ScopeGraphRealConfigValidationTest.ragDocs(profile).stream()
                .map(StaticDocEntry::ragScope)
                .toList());
        scopes.add(TaggingQuestions.MASTER_DOMAIN);
        assertThat(domain.options()).containsExactlyElementsOf(scopes);
        assertThat(domain.options()).hasSizeBetween(2, TaggingQuestions.MAX_OPTIONS);
        assertThat(domain.options()).doesNotContainAnyElementsOf(UNMAPPED_TOOL_DOMAINS);
        // Every option carries its curated sentence, verbatim from entities.yaml.
        for (String option : domain.options()) {
            assertThat(domain.choiceCriteria().get(option))
                    .as("sentence for %s", option)
                    .isEqualTo(LEXICON.domains().get(option));
        }
        assertThat(domain.choiceCriteria().get("master")).startsWith("No single area");
        // TierSelector's risky domains are spelled in this vocabulary.
        assertThat(domain.options()).contains("accounting", "tax", "admin", "security");
    }

    @Test
    @DisplayName(
            "spec §2.3: every fixed tag but domain and entity is asked, each with instructions and the right primitive")
    void fixedQuestionsCoverEveryTag() {
        List<TagQuestion> fixed = TaggingQuestions.fixedQuestions();

        assertThat(fixed.stream().map(TagQuestion::tag))
                .containsExactlyInAnyOrderElementsOf(Stream.of(TagName.values())
                        .filter(tag -> tag != TagName.DOMAIN && tag != TagName.ENTITY)
                        .toList());
        for (TagQuestion question : fixed) {
            assertThat(question.wireName()).isEqualTo(question.tag().wireName());
            assertThat(question.toWire()).containsKey("instructions");
            assertThat(question.toWire().get("type"))
                    .isEqualTo(question.primitive().wireName());
        }
        TagQuestion risk =
                fixed.stream().filter(q -> q.tag() == TagName.RISK).findFirst().orElseThrow();
        assertThat(risk.scoreLevels()).containsExactly("LOW", "MEDIUM", "HIGH");
        assertThat(risk.toWire().get("criteria")).isEqualTo(List.of("LOW", "MEDIUM", "HIGH"));
        TagQuestion workflow = fixed.stream()
                .filter(q -> q.tag() == TagName.WORKFLOW_STATE)
                .findFirst()
                .orElseThrow();
        // Every WorkflowState value is an option, in enum order.
        assertThat(workflow.options())
                .containsExactly("IDLE", "CREATING_PO", "RECEIVING_ASN", "INVENTORY_RECON", "PROCESSING_RETURN");
        assertThat(workflow.choiceCriteria().get("IDLE")).startsWith("Not in a specific workflow");
        assertThat(workflow.choiceCriteria().get("PROCESSING_RETURN")).startsWith("Processing a customer's return");
        TagQuestion intent = fixed.stream()
                .filter(q -> q.tag() == TagName.INTENT)
                .findFirst()
                .orElseThrow();
        assertThat(intent.options()).containsExactly("QUERY", "ACTION", "UNKNOWN");
        TagQuestion complexity = fixed.stream()
                .filter(q -> q.tag() == TagName.COMPLEXITY)
                .findFirst()
                .orElseThrow();
        assertThat(complexity.options()).containsExactly("SINGLE_LOOKUP", "MULTI_DOMAIN");
    }

    @Test
    @DisplayName("one Noul per lexicon entity, named entity_<key>, listing the en, fr and es terms")
    void entityQuestionsAreOneNoulPerEntity() {
        List<TagQuestion> questions =
                TaggingQuestions.build(LEXICON, preload("default")).questions();
        List<TagQuestion> entities =
                questions.stream().filter(q -> q.tag() == TagName.ENTITY).toList();

        assertThat(entities).hasSize(LEXICON.entities().size());
        assertThat(entities).allMatch(q -> q.primitive() == TagName.Primitive.NOUL);
        assertThat(entities).extracting(TagQuestion::wireName).isSorted();
        for (EntityLexicon.EntityDefinition entity : LEXICON.entities()) {
            TagQuestion question = entities.stream()
                    .filter(q -> q.wireName().equals("entity_" + entity.key()))
                    .findFirst()
                    .orElseThrow();
            assertThat(TagName.entityKey(question.wireName())).contains(entity.key());
            String expected = "Is this message about a "
                    + String.join(", ", entity.terms().get("en")) + " ("
                    + String.join(", ", entity.terms().get("fr")) + "; "
                    + String.join(", ", entity.terms().get("es"))
                    + ")?";
            assertThat(question.instructions()).endsWith(expected);
            assertThat(question.toWire()).doesNotContainKey("criteria");
        }
        // A threshold of "entity" covers every entity Noul; a per-entity one may override it.
        TaggingProperties properties =
                new TaggingProperties(null, null, null, Map.of("entity", 0.6, "entity.work-order", 0.9), 0);
        assertThat(properties.thresholdFor("entity_invoice")).isEqualTo(0.6);
        assertThat(properties.thresholdFor("entity_work-order")).isEqualTo(0.9);
        assertThat(properties.thresholdFor("simple_chat")).isEqualTo(TaggingProperties.DEFAULT_THRESHOLD);
    }

    @Test
    @DisplayName("with no preload list only master is left, and a one-option Choice is not asked")
    void withoutPreloadNoDomainQuestion() {
        TagQuestion bare = TaggingQuestions.domainQuestion(LEXICON, null);
        assertThat(bare.options()).containsExactly("master");
        assertThat(TaggingQuestions.build(LEXICON, null).questions())
                .noneMatch(question -> question.tag() == TagName.DOMAIN);
        // A scope the lexicon does not describe still gets a sentence (the real-config test forbids it).
        StaticRagPreloadProperties preload =
                new StaticRagPreloadProperties(List.of(new StaticDocEntry("x", "classpath:x.md", "unknown-scope")));
        TagQuestion withUnknown = TaggingQuestions.domainQuestion(LEXICON, preload);
        assertThat(withUnknown.options()).containsExactly("master", "unknown-scope");
        assertThat(withUnknown.choiceCriteria().get("unknown-scope")).contains("unknown-scope");
    }

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"default", "alpha"})
    @DisplayName(
            "mcp.tagging.entity-questions=false asks the 13 fixed questions only and hashes the domain options alone")
    void entityQuestionsOffAsksTheFixedQuestionsOnly(String profile) throws Exception {
        TaggingQuestions.QuestionSet on = TaggingQuestions.build(LEXICON, preload(profile), true);
        TaggingQuestions.QuestionSet off = TaggingQuestions.build(LEXICON, preload(profile), false);

        assertThat(off.questions()).hasSize(13);
        assertThat(off.questions()).noneMatch(question -> question.tag() == TagName.ENTITY);
        assertThat(off.questions()).containsExactlyElementsOf(on.questions().subList(0, 13));
        assertThat(off.optionListHash()).isNotEqualTo(on.optionListHash());
        assertThat(off.optionListHash())
                .isEqualTo(TaggingQuestions.optionListHash(
                        TaggingQuestions.domainQuestion(LEXICON, preload(profile))
                                .options(),
                        List.of()));
        // The property drives the bean: default true, false honoured, the older 5-arg shape asks them.
        assertThat(new TaggingProperties(null, null, null, null, 0).entityQuestions())
                .isTrue();
        assertThat(new TaggingProperties(null, null, null, null, 0, false).entityQuestions())
                .isFalse();
        assertThat(new TaggingQuestions(LEXICON, preload(profile), false).questions())
                .hasSize(13);
        assertThat(new TaggingQuestions(LEXICON, preload(profile)).questions()).hasSize(44);
        byte[] body = new ObjectMapper()
                .writeValueAsBytes(JevClient.requestBody(
                        TaggingProperties.Provider.defaults(),
                        "which customers haven't bought in the last 90 days but spent over $10,000 in the prior year?",
                        off.questions()));
        System.out.printf(
                "tagging questions profile=%s entity-questions=false count=%d bodyBytes=%d optionListHash=%s%n",
                profile, off.size(), body.length, off.optionListHash());
    }

    @Test
    @DisplayName("the option-list hash identifies the domain options and entity keys asked, whatever their order")
    void optionListHashIsDeterministic() {
        String hash = TaggingQuestions.build(LEXICON, preload("default")).optionListHash();
        assertThat(hash).hasSize(16).matches("[0-9a-f]+");
        assertThat(TaggingQuestions.build(LEXICON, preload("default")).optionListHash())
                .isEqualTo(hash);
        assertThat(TaggingQuestions.build(LEXICON, preload("alpha")).optionListHash())
                .isEqualTo(hash);
        assertThat(TaggingQuestions.build(LEXICON, null).optionListHash()).isNotEqualTo(hash);
        EntityLexicon fewer = new EntityLexicon(
                LEXICON.domainScopes(),
                LEXICON.entities().subList(1, LEXICON.entities().size()),
                List.of(),
                LEXICON.domains());
        assertThat(TaggingQuestions.build(fewer, preload("default")).optionListHash())
                .isNotEqualTo(hash);
        assertThat(TaggingQuestions.optionListHash(List.of("b", "a"), List.of()))
                .isNotEqualTo(TaggingQuestions.optionListHash(List.of("a", "b"), List.of()));
    }

    @Test
    @DisplayName("the set is built once per context")
    void builtOnce() {
        TaggingQuestions questions = new TaggingQuestions(LEXICON, preload("default"));
        assertThat(questions.questions()).isSameAs(questions.questions());
        assertThat(questions.questionSet().size())
                .isEqualTo(questions.questions().size());
    }
}
