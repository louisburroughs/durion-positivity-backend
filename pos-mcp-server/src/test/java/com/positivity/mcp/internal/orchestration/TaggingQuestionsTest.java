package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.client.JevClient;
import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.config.StaticRagPreloadProperties;
import com.positivity.mcp.internal.config.StaticRagPreloadProperties.StaticDocEntry;
import com.positivity.mcp.internal.config.TaggingProperties;
import com.positivity.mcp.internal.domain.TagName;
import com.positivity.mcp.internal.domain.TagQuestion;
import com.positivity.mcp.internal.domain.TaggingMode;
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

        // 12 fixed tags + domain + one Noul per lexicon entity (32 today, sales-report since #2384): 45, well under 64.
        assertThat(questions).hasSize(12 + 1 + LEXICON.entities().size());
        assertThat(questions).hasSizeBetween(45, 47);
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
        assertThat(domain.instructions()).endsWith("social chat, or the assistant itself.");
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
        // Wording from the NLTI domain review (what is sent to the provider, never acted on in Wave 1).
        assertThat(workflow.instructions()).endsWith("Choose IDLE when unsure.");
        assertThat(workflow.choiceCriteria().get("RECEIVING_ASN")).contains("an advance shipping notice (ASN)");
        assertThat(intent.choiceCriteria().get("ACTION")).contains("\"cancel it\"");
        assertThat(intent.choiceCriteria().get("UNKNOWN"))
                .endsWith("A short request to change something is ACTION, not UNKNOWN.");
        assertThat(complexity.instructions())
                .endsWith("How much work does this message need: one lookup or one change in one area, or several"
                        + " steps or areas?");
        assertThat(risk.instructions()).endsWith("When unsure between two levels, choose the higher.");
        assertThat(fixed.stream().map(TagQuestion::instructions))
                .noneMatch(text -> text.contains("work order"))
                .noneMatch(text -> text.contains("advanced shipment"));
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
            String expected = "Is this message about any of these: "
                    + String.join(", ", entity.terms().get("en")) + " (French: "
                    + String.join(", ", entity.terms().get("fr")) + "; Spanish: "
                    + String.join(", ", entity.terms().get("es"))
                    + ")?";
            assertThat(question.instructions()).endsWith(expected);
            assertThat(question.toWire()).doesNotContainKey("criteria");
        }
        // A threshold of "entity" covers every entity Noul; a per-entity one may override it.
        TaggingProperties properties =
                new TaggingProperties(null, null, null, Map.of("entity", 0.6, "entity.workorder", 0.9), 0);
        assertThat(properties.thresholdFor("entity_invoice")).isEqualTo(0.6);
        assertThat(properties.thresholdFor("entity_workorder")).isEqualTo(0.9);
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
        // The property drives the bean: default false (the 5-arg shape takes the default), false honoured.
        assertThat(new TaggingProperties(null, null, null, null, 0).entityQuestions())
                .isFalse();
        assertThat(new TaggingProperties(null, null, null, null, 0, true).entityQuestions())
                .isTrue();
        assertThat(new TaggingProperties(null, null, null, null, 0, false).entityQuestions())
                .isFalse();
        assertThat(new TaggingQuestions(LEXICON, preload(profile), false).questions())
                .hasSize(13);
        assertThat(new TaggingQuestions(LEXICON, preload(profile)).questions()).hasSize(45);
        byte[] body = new ObjectMapper()
                .writeValueAsBytes(JevClient.requestBody(
                        TaggingProperties.Provider.defaults(),
                        "which customers haven't bought in the last 90 days but spent over $10,000 in the prior year?",
                        off.questions()));
        System.out.printf(
                "tagging questions profile=%s entity-questions=false count=%d bodyBytes=%d optionListHash=%s%n",
                profile, off.size(), body.length, off.optionListHash());
    }

    /**
     * PR #2367 review: the default model ({@code tev1:0.8b}) reads about 2,000 tokens, which the 45-question
     * set (a ~22 KB request body, as the test above prints it) overflows. The defaults, as {@code
     * application.yml} binds them with no environment override and as the record defaults them, must ask the
     * fixed set only.
     */
    @Test
    @DisplayName("the request built from the default properties asks the fixed questions only, no entity Nouls")
    void defaultPropertiesAskTheFixedQuestionsOnly() throws Exception {
        org.springframework.core.env.StandardEnvironment environment =
                new org.springframework.core.env.StandardEnvironment();
        // The default (profile-less) document of application.yml, placeholders resolved to their defaults.
        environment
                .getPropertySources()
                .addLast(new org.springframework.boot.env.YamlPropertySourceLoader()
                        .load("application.yml", new org.springframework.core.io.ClassPathResource("application.yml"))
                        .getFirst());
        TaggingProperties bound = org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("mcp.tagging", TaggingProperties.class)
                .get();
        assertThat(bound.provider().model()).isEqualTo(TaggingProperties.Provider.DEFAULT_MODEL);
        assertThat(bound.entityQuestions()).as("application.yml default").isFalse();

        for (TaggingProperties defaults : List.of(
                bound, TaggingProperties.off(), TaggingProperties.shadow(TaggingProperties.Provider.defaults()))) {
            // The scope graph on, so only mcp.tagging.entity-questions decides.
            List<TagQuestion> asked =
                    new TaggingQuestions(preload("default"), defaults, SCOPE_GRAPH_SHADOW).questions();
            Map<String, Object> request =
                    JevClient.requestBody(defaults.provider(), "how many tires do we have on hand?", asked);
            @SuppressWarnings("unchecked")
            Map<String, Object> wireQuestions = (Map<String, Object>) request.get("questions");

            List<TagQuestion> fixedAndDomain = new java.util.ArrayList<>(TaggingQuestions.fixedQuestions());
            fixedAndDomain.add(TaggingQuestions.domainQuestion(LEXICON, preload("default")));
            assertThat(asked).containsExactlyElementsOf(fixedAndDomain);
            assertThat(asked).noneMatch(question -> question.tag() == TagName.ENTITY);
            assertThat(wireQuestions.keySet())
                    .hasSize(13)
                    .noneMatch(name -> name.startsWith("entity_"))
                    .containsExactlyElementsOf(
                            fixedAndDomain.stream().map(TagQuestion::wireName).toList());
        }
        // And with no properties bean at all.
        assertThat(new TaggingQuestions(preload("default"), null, SCOPE_GRAPH_SHADOW).questions())
                .noneMatch(question -> question.tag() == TagName.ENTITY)
                .hasSize(13);
    }

    private static final ScopeGraphProperties SCOPE_GRAPH_SHADOW =
            new ScopeGraphProperties(ScopeGraphProperties.Mode.SHADOW, List.of(), 0, 0, 0);

    /**
     * NLTI domain review (ADR-0068): the entity Nouls are asked only when the scope graph, their one
     * consumer, is on AND {@code mcp.tagging.entity-questions} is true. Either gate alone withholds them.
     */
    @ParameterizedTest(name = "entity-questions={0} scope-graph={1} → asked={2}")
    @org.junit.jupiter.params.provider.CsvSource({
        "true,  SHADOW,  true",
        "true,  ENFORCE, true",
        "true,  OFF,     false",
        "true,  ABSENT,  false",
        "false, SHADOW,  false",
        "false, ENFORCE, false",
        "false, OFF,     false"
    })
    void entityNoulsNeedTheScopeGraphOnAndEntityQuestions(
            boolean entityQuestions, String scopeGraphMode, boolean asked) {
        TaggingProperties tagging =
                new TaggingProperties(TaggingMode.SHADOW, List.of(), null, Map.of(), 0, entityQuestions);
        ScopeGraphProperties scopeGraph = scopeGraphMode.equals("ABSENT")
                ? null
                : new ScopeGraphProperties(ScopeGraphProperties.Mode.valueOf(scopeGraphMode), List.of(), 0, 0, 0);

        assertThat(TaggingQuestions.asksEntityQuestions(tagging, scopeGraph)).isEqualTo(asked);
        List<TagQuestion> questions = new TaggingQuestions(preload("default"), tagging, scopeGraph).questions();
        if (asked) {
            assertThat(questions.stream().filter(question -> question.tag() == TagName.ENTITY))
                    .hasSize(LEXICON.entities().size());
            assertThat(questions)
                    .hasSize(13 + LEXICON.entities().size())
                    .hasSizeLessThanOrEqualTo(TaggingQuestions.MAX_QUESTIONS);
        } else {
            assertThat(questions).noneMatch(question -> question.tag() == TagName.ENTITY);
            assertThat(questions).hasSize(13);
        }
    }

    @Test
    @DisplayName("a domain option list over the 26-option local-model limit skips the domain question, logged once,"
            + " and the option-list hash then covers no domain options")
    void domainQuestionSkippedAboveTheOptionCap() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(TaggingQuestions.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            // 26 scopes + master = 27 options: one over the cap.
            TaggingQuestions over = new TaggingQuestions(LEXICON, scopes(TaggingQuestions.MAX_OPTIONS), false);
            assertThat(over.questions()).noneMatch(question -> question.tag() == TagName.DOMAIN);
            assertThat(over.questions()).containsExactlyElementsOf(TaggingQuestions.fixedQuestions());
            assertThat(over.questions())
                    .allSatisfy(question ->
                            assertThat(question.options()).hasSizeLessThanOrEqualTo(TaggingQuestions.MAX_OPTIONS));
            assertThat(logs.list)
                    .filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .singleElement()
                    .satisfies(event -> assertThat(event.getFormattedMessage())
                            .contains("27")
                            .contains(Integer.toString(TaggingQuestions.MAX_OPTIONS)));
            // The set is built once: asking for it again logs nothing more.
            over.questions();
            over.questionSet();
            assertThat(logs.list).hasSize(1);

            // 25 scopes + master = 26 options: at the cap, still asked.
            TaggingQuestions atCap = new TaggingQuestions(LEXICON, scopes(TaggingQuestions.MAX_OPTIONS - 1), false);
            assertThat(atCap.questions())
                    .filteredOn(question -> question.tag() == TagName.DOMAIN)
                    .singleElement()
                    .satisfies(domain -> assertThat(domain.options()).hasSize(TaggingQuestions.MAX_OPTIONS));
            assertThat(logs.list).hasSize(1);

            // PR #2367 review: the hash names the option lists asked. The skipped question hashes as
            // no domain options at all, not as the 27 it would have offered, so it differs from the
            // asked case and equals the hash of a set with no domain question.
            String noDomainHash = TaggingQuestions.optionListHash(List.of(), List.of());
            assertThat(over.questionSet().optionListHash())
                    .isNotEqualTo(atCap.questionSet().optionListHash())
                    .isEqualTo(noDomainHash)
                    .isNotEqualTo(TaggingQuestions.optionListHash(
                            TaggingQuestions.domainQuestion(LEXICON, scopes(TaggingQuestions.MAX_OPTIONS))
                                    .options(),
                            List.of()));
            assertThat(atCap.questionSet().optionListHash())
                    .isEqualTo(TaggingQuestions.optionListHash(
                            TaggingQuestions.domainQuestion(LEXICON, scopes(TaggingQuestions.MAX_OPTIONS - 1))
                                    .options(),
                            List.of()));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    /**
     * PR #2367 review: a domain Choice of fewer than two options is skipped like one over the cap: one
     * WARN naming counts only, and the option-list hash covers no domain options. Master is always an
     * option, so a built set sees one at least; zero is defended at the guard itself.
     */
    @Test
    @DisplayName("a domain option list under two options skips the domain question with exactly one WARN (counts"
            + " only), hashed as no domain options")
    void domainQuestionSkippedBelowTwoOptions() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(TaggingQuestions.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            for (int options = 0; options < 2; options++) {
                logs.list.clear();
                assertThat(TaggingQuestions.asksDomain(options)).isFalse();
                assertOneCountOnlyWarn(logs, options);
            }
            assertThat(TaggingQuestions.asksDomain(2)).isTrue();
            assertThat(TaggingQuestions.asksDomain(TaggingQuestions.MAX_OPTIONS))
                    .isTrue();

            // End to end: with no preload list, or one whose docs name no rag scope, only master is
            // left. The question is skipped, hashed as no domain options (unlike a set that asks it),
            // and the set, built once, logs exactly one WARN.
            String noDomainHash = TaggingQuestions.optionListHash(List.of(), List.of());
            String askedHash = new TaggingQuestions(LEXICON, scopes(1), false)
                    .questionSet()
                    .optionListHash();
            for (StaticRagPreloadProperties lonely :
                    java.util.Arrays.asList(null, new StaticRagPreloadProperties(List.of()))) {
                logs.list.clear();
                TaggingQuestions masterOnly = new TaggingQuestions(LEXICON, lonely, false);
                assertThat(masterOnly.questions()).containsExactlyElementsOf(TaggingQuestions.fixedQuestions());
                assertThat(masterOnly.questionSet().optionListHash())
                        .isEqualTo(noDomainHash)
                        .isNotEqualTo(askedHash)
                        .isNotEqualTo(
                                TaggingQuestions.optionListHash(List.of(TaggingQuestions.MASTER_DOMAIN), List.of()));
                masterOnly.questions();
                masterOnly.questionSet();
                assertOneCountOnlyWarn(logs, 1);
            }
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    private static void assertOneCountOnlyWarn(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs, int options) {
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("Tagging domain question skipped: " + options
                            + " rag-scope option(s), a Choice needs at least 2");
        });
    }

    /** A preload list of {@code count} distinct rag scopes. */
    private static StaticRagPreloadProperties scopes(int count) {
        List<StaticDocEntry> docs = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            String scope = String.format(java.util.Locale.ROOT, "scope-%02d", index);
            docs.add(new StaticDocEntry(scope, "classpath:" + scope + ".md", scope));
        }
        return new StaticRagPreloadProperties(docs);
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
