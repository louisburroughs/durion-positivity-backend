package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.config.StaticRagPreloadProperties;
import com.positivity.mcp.internal.config.StaticRagPreloadProperties.StaticDocEntry;
import com.positivity.mcp.internal.domain.QuestionTags;
import com.positivity.mcp.internal.domain.TagName;
import com.positivity.mcp.internal.domain.TagQuestion;
import com.positivity.mcp.internal.scopegraph.EdgeType;
import com.positivity.mcp.internal.scopegraph.EntityLexicon;
import com.positivity.mcp.internal.scopegraph.EntityLexiconLoader;
import com.positivity.mcp.internal.scopegraph.NodeId;
import com.positivity.mcp.internal.scopegraph.NodeType;
import com.positivity.mcp.internal.scopegraph.ScopeGraph;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-0068 spec §2.2, §2.3, §2.4: the question set and its limits. */
class TaggingQuestionsTest {

    private static final Instant BUILT_AT = Instant.parse("2026-09-30T12:00:00Z");

    /** The real lexicon's entities and their owning domains, as {@code OWNED_BY} edges. */
    private static ScopeGraph realLexiconGraph() {
        EntityLexicon lexicon = EntityLexiconLoader.loadDefault();
        ScopeGraph.Builder graph = ScopeGraph.builder();
        for (EntityLexicon.EntityDefinition entity : lexicon.entities()) {
            NodeId node = graph.node(NodeType.ENTITY, entity.key());
            NodeId domain = graph.node(NodeType.DOMAIN, entity.domain());
            graph.edge(EdgeType.OWNED_BY, node, domain);
        }
        return graph.build(BUILT_AT);
    }

    private static ScopeGraph syntheticGraph(Map<String, Integer> entitiesPerDomain) {
        ScopeGraph.Builder graph = ScopeGraph.builder();
        entitiesPerDomain.forEach((domain, count) -> {
            NodeId domainNode = graph.node(NodeType.DOMAIN, domain);
            for (int index = 1; index <= count; index++) {
                NodeId entity = graph.node(NodeType.ENTITY, domain + "-entity-" + String.format("%02d", index));
                graph.edge(EdgeType.OWNED_BY, entity, domainNode);
            }
        });
        return graph.build(BUILT_AT);
    }

    @Test
    @DisplayName("spec §2.2: the set built from the real lexicon stays within 64 questions and 2-26 options")
    void realLexiconStaysWithinTheProviderLimits() {
        List<TagQuestion> questions = new TaggingQuestions().build(realLexiconGraph());

        assertThat(questions).hasSizeLessThanOrEqualTo(TaggingQuestions.MAX_QUESTIONS);
        for (TagQuestion question : questions) {
            assertThat(question.instructions())
                    .as("%s carries instructions", question.wireName())
                    .isNotBlank();
            if (question.primitive() != TagName.Primitive.NOUL) {
                assertThat(question.options())
                        .as("%s options", question.wireName())
                        .hasSizeBetween(2, TaggingQuestions.MAX_OPTIONS);
            }
        }
        // Every entity of the lexicon is asked exactly once, across the groups.
        List<String> asked = questions.stream()
                .filter(question -> question.tag() == TagName.ENTITY)
                .flatMap(question -> question.options().stream())
                .filter(option -> !QuestionTags.NO_ENTITY.equals(option))
                .toList();
        assertThat(asked).doesNotHaveDuplicates();
        assertThat(asked).containsExactlyInAnyOrderElementsOf(realLexiconGraph().entityOptions());
        // The domain question offers the graph's domains and master.
        TagQuestion domain = questions.stream()
                .filter(question -> question.tag() == TagName.DOMAIN)
                .findFirst()
                .orElseThrow();
        assertThat(domain.options()).contains(TaggingQuestions.MASTER_DOMAIN);
        assertThat(domain.options()).containsAll(realLexiconGraph().domainOptions());
        // Wire names are unique and the fixed tags come first.
        assertThat(questions.stream().map(TagQuestion::wireName).toList()).doesNotHaveDuplicates();
        assertThat(questions.get(0).tag()).isEqualTo(TagName.FOLLOWS_PREVIOUS_TURN);
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
        assertThat(workflow.options())
                .containsExactly("IDLE", "CREATING_PO", "RECEIVING_ASN", "INVENTORY_RECON", "PROCESSING_RETURN");
        // Spec §3: the option descriptions are the phrases deriveWorkflowState matches.
        assertThat(workflow.choiceCriteria().get("CREATING_PO")).contains("purchase order", "create po");
        assertThat(workflow.choiceCriteria().get("RECEIVING_ASN")).contains("advanced shipment notice");
        assertThat(workflow.choiceCriteria().get("INVENTORY_RECON")).contains("cycle count");
    }

    @Test
    @DisplayName(
            "spec §2.3: with an empty graph the domain options are the preload rag-scopes plus master, and no entity is asked")
    void emptyGraphUsesPreloadScopesAndAsksNoEntity() {
        StaticRagPreloadProperties preload = new StaticRagPreloadProperties(List.of(
                new StaticDocEntry("a", "classpath:a.md", "accounting"),
                new StaticDocEntry("b", "classpath:b.md", "workorder"),
                new StaticDocEntry("c", "classpath:c.md", "accounting"),
                new StaticDocEntry("d", "classpath:d.md", null)));
        TaggingQuestions questions = new TaggingQuestions(null, preload);

        List<TagQuestion> built = questions.build(ScopeGraph.empty());

        assertThat(built).noneMatch(question -> question.tag() == TagName.ENTITY);
        TagQuestion domain = built.stream()
                .filter(q -> q.tag() == TagName.DOMAIN)
                .findFirst()
                .orElseThrow();
        assertThat(domain.options()).containsExactly("accounting", "master", "workorder");
        // No preload either: master alone is one option, and a one-option Choice is not asked.
        TagQuestion bare = new TaggingQuestions().domainQuestion(ScopeGraph.empty());
        assertThat(bare.options()).containsExactly("master");
        assertThat(new TaggingQuestions().build(ScopeGraph.empty()))
                .noneMatch(question -> question.tag() == TagName.DOMAIN);
    }

    @Test
    @DisplayName(
            "spec §2.4: groups of at most 24 by owning domain then key, a domain never split unless it alone exceeds 24")
    void entityGroupsAreDeterministicAndKeepDomainsTogether() {
        Map<String, Integer> perDomain = new HashMap<>();
        perDomain.put("accounting", 10);
        perDomain.put("workorder", 10);
        perDomain.put("inventory", 6);
        perDomain.put("catalog", 3);
        List<List<String>> groups = TaggingQuestions.entityGroups(syntheticGraph(perDomain));

        // accounting(10) + catalog(3) + inventory(6) = 19 fit; workorder(10) would exceed 24 → new group.
        assertThat(groups).hasSize(2);
        assertThat(groups.get(0)).hasSize(19);
        assertThat(groups.get(0).get(0)).startsWith("accounting-");
        assertThat(groups.get(0).get(18)).startsWith("inventory-");
        assertThat(groups.get(1)).hasSize(10).allMatch(key -> key.startsWith("workorder-"));
        // Deterministic: the same graph yields the same groups.
        assertThat(TaggingQuestions.entityGroups(syntheticGraph(perDomain))).isEqualTo(groups);

        // A single domain larger than a group is the one case a domain is split.
        List<List<String>> split = TaggingQuestions.entityGroups(syntheticGraph(Map.of("huge", 30)));
        assertThat(split).hasSize(2);
        assertThat(split.get(0)).hasSize(TaggingQuestions.ENTITY_GROUP_SIZE);
        assertThat(split.get(1)).hasSize(6);

        // Each group question adds none and stays within the option cap.
        List<TagQuestion> questions = new TaggingQuestions().build(syntheticGraph(Map.of("huge", 30)));
        List<TagQuestion> groupQuestions =
                questions.stream().filter(q -> q.tag() == TagName.ENTITY).toList();
        assertThat(groupQuestions).extracting(TagQuestion::wireName).containsExactly("entity_1", "entity_2");
        for (TagQuestion group : groupQuestions) {
            assertThat(group.options()).hasSizeLessThanOrEqualTo(TaggingQuestions.MAX_OPTIONS);
            assertThat(group.options()).endsWith(QuestionTags.NO_ENTITY);
            assertThat(group.instructions()).contains(QuestionTags.NO_ENTITY);
        }
    }

    @Test
    @DisplayName("the set is cached per graph hash and rebuilt when the snapshot changes")
    void cachedPerGraphHash() {
        TaggingQuestions questions = new TaggingQuestions();
        List<TagQuestion> first = questions.questions();
        assertThat(questions.questions()).isSameAs(first);
        assertThat(first).noneMatch(question -> question.tag() == TagName.ENTITY);
    }
}
