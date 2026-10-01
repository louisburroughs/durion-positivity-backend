package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.classification.SimpleChatRuleDefaults;
import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.domain.QuestionTags;
import com.positivity.mcp.internal.domain.TagAnswer;
import com.positivity.mcp.internal.domain.TagName;
import com.positivity.mcp.internal.domain.TagSource;
import com.positivity.mcp.internal.domain.TaggingMode;
import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolSelectionContext;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import com.positivity.mcp.internal.orchestration.tools.DateWindowFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.ExaWebSearchTool;
import com.positivity.mcp.internal.orchestration.tools.GlossaryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.InventoryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.OrderFacadeTool;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import com.positivity.mcp.internal.repository.ToolPriorityRepository;
import com.positivity.mcp.internal.scopegraph.ScopeResolverFixtures;
import com.positivity.mcp.internal.service.RolePromptResolver;
import com.positivity.mcp.internal.service.TenantToolPriorityResolver;
import com.positivity.mcp.internal.service.ToolRegistryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.web.client.RestClient;

/**
 * ADR-0068 spec §2.6, §4: what each consumer does with an enforced tag, against fixed {@link
 * QuestionTags} records: the simple-chat override, the admin fast-path veto in both directions, the
 * compound gate and widened splitter, and the {@code lookups} facade replacement. Every consumer keeps
 * today's behaviour on a heuristic record and on {@link QuestionTags#none()}.
 */
class TaggingEnforceConsumersTest {

    private static final HeuristicQuestionTagger HEURISTIC = HeuristicQuestionTagger.withDefaultCatalog();

    /** {@code message}'s heuristic record with the given tags replaced by model answers at 0.95. */
    static QuestionTags enforced(String message, Map<TagName, String> modelValues) {
        QuestionTags heuristic = HEURISTIC.tag(message);
        Map<String, TagAnswer> acting = new LinkedHashMap<>(heuristic.heuristic());
        Map<String, TagAnswer> model = new LinkedHashMap<>();
        modelValues.forEach((tag, value) -> {
            TagAnswer answer = tag.primitive() == TagName.Primitive.NOUL
                    ? TagAnswer.noul(Boolean.parseBoolean(value) ? 0.95 : 0.05)
                    : new TagAnswer(value, 0.95, TagSource.JEV);
            model.put(tag.wireName(), answer);
            acting.put(tag.wireName(), answer);
        });
        return new QuestionTags(TaggingMode.ENFORCE, heuristic.heuristic(), model, acting, null, "stub", 5L, false);
    }

    @Nested
    @DisplayName("SimpleChatFastPath")
    class SimpleChat {

        private final SimpleChatClassifier classifier =
                new SimpleChatClassifier(SimpleChatRuleDefaults.defaultCatalog());
        private final SimpleChatFastPath fastPath = new SimpleChatFastPath(
                classifier,
                mock(RolePromptResolver.class),
                new SharedOrchestrationSupport(Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC)));

        @Test
        @DisplayName("an enforced simple_chat decides in both directions")
        void enforcedSimpleChatDecides() {
            assertThat(classifier.isSimpleChat("ok cool")).isTrue();
            assertThat(fastPath.isSimpleChat("ok cool", enforced("ok cool", Map.of(TagName.SIMPLE_CHAT, "false"))))
                    .isFalse();
            String task = "show me open work orders";
            assertThat(classifier.isSimpleChat(task)).isFalse();
            assertThat(fastPath.isSimpleChat(task, enforced(task, Map.of(TagName.SIMPLE_CHAT, "true"))))
                    .isTrue();
        }

        @Test
        @DisplayName("an enforced follows_previous_turn true forces false whatever simple_chat says")
        void followsPreviousTurnOverrides() {
            QuestionTags tags =
                    enforced("ok cool", Map.of(TagName.SIMPLE_CHAT, "true", TagName.FOLLOWS_PREVIOUS_TURN, "true"));
            assertThat(fastPath.isSimpleChat("ok cool", tags)).isFalse();

            QuestionTags notFollowing =
                    enforced("ok cool", Map.of(TagName.SIMPLE_CHAT, "true", TagName.FOLLOWS_PREVIOUS_TURN, "false"));
            assertThat(fastPath.isSimpleChat("ok cool", notFollowing)).isTrue();
        }

        @Test
        @DisplayName("a heuristic follows_previous_turn does not override: shadow keeps today's decision")
        void heuristicCueDoesNotOverride() {
            // An exact catalog hit that also carries a continuation cue ("again"): today's classifier
            // says simple chat, and so must the shadow record.
            String message = "thanks again";
            QuestionTags heuristic = HEURISTIC.tag(message);
            assertThat(heuristic.followsPreviousTurn()).isTrue();
            assertThat(heuristic.simpleChat()).isEqualTo(classifier.isSimpleChat(message));
            assertThat(fastPath.isSimpleChat(message, heuristic)).isEqualTo(classifier.isSimpleChat(message));
            assertThat(fastPath.isSimpleChat(message, QuestionTags.none())).isEqualTo(classifier.isSimpleChat(message));
        }
    }

    @Nested
    @DisplayName("ToolRegistryService admin fast path (ADR-0068 §3.4)")
    class AdminFastPath {

        private static final Set<String> CODES = Set.of("AUTHENTICATED", "admin:user:view");
        private static final String ADMIN = "list all users";
        private static final String PLAIN = "tell me about the shop";

        private final ToolRegistryService service = service();

        private static ToolRegistryService service() {
            ToolMetadataRepository repository = mock(ToolMetadataRepository.class);
            ToolMetadata adminTool = new ToolMetadata(
                    UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    "AdminFacadeTool",
                    "Admin",
                    "Administration",
                    "admin",
                    1.0,
                    "low",
                    50,
                    true,
                    "adminFacadeTool");
            when(repository.findEnabledByPermissionsAndWorkflow(any(), anyString()))
                    .thenReturn(List.of(adminTool));
            when(repository.findTopKByEmbeddingForPermissions(any(), anyInt(), any(), anyString()))
                    .thenReturn(List.of());
            org.springframework.ai.embedding.EmbeddingModel embeddingModel =
                    mock(org.springframework.ai.embedding.EmbeddingModel.class);
            when(embeddingModel.embed(anyString())).thenReturn(new float[] {0f});
            return new ToolRegistryService(
                    repository, embeddingModel, new TenantToolPriorityResolver(mock(ToolPriorityRepository.class)));
        }

        private boolean fires(String message, QuestionTags tags) {
            return service.resolveCandidateSelection(
                            new ToolSelectionContext(message, "ROLE_ADMIN", "IDLE", CODES), 8, tags)
                    .adminFastPath();
        }

        @Test
        @DisplayName("keyword match, heuristic record or none(): fires as today")
        void firesOnTheHeuristic() {
            assertThat(fires(ADMIN, HEURISTIC.tag(ADMIN))).isTrue();
            assertThat(fires(ADMIN, QuestionTags.none())).isTrue();
            assertThat(fires(PLAIN, HEURISTIC.tag(PLAIN))).isFalse();
        }

        @Test
        @DisplayName("model false at or above threshold with a keyword match: not fired")
        void modelFalseVetoes() {
            assertThat(fires(ADMIN, enforced(ADMIN, Map.of(TagName.ADMIN_ACCOUNT_QUESTION, "false"))))
                    .isFalse();
        }

        @Test
        @DisplayName("model true without a keyword match: never fired by the tag alone")
        void modelTrueNeverFiresAlone() {
            assertThat(fires(PLAIN, enforced(PLAIN, Map.of(TagName.ADMIN_ACCOUNT_QUESTION, "true"))))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("RerankedContentRetriever compound gate")
    class CompoundGate {

        private static final String EN =
                "what does an invoice number look like and what permission is needed to read an invoice";
        private static final String FR =
                "quel est le solde du client Tremblay et combien de bons de travail sont ouverts";
        private static final String ES =
                "cuál es el saldo del cliente Pérez y cuántas órdenes de trabajo están abiertas";
        private static final String SINGLE = "returns and refunds";

        @Test
        @DisplayName("the widened splitter splits at a conjunction without the starter-word check, en/fr/es")
        void widenedSplitterSplitsOnConjunctions() {
            assertThat(RerankedContentRetriever.splitSubQueries(FR, 3)).hasSize(1);
            assertThat(RerankedContentRetriever.splitSubQueriesWidened(FR, 3))
                    .containsExactly("quel est le solde du client Tremblay", "combien de bons de travail sont ouverts");
            assertThat(RerankedContentRetriever.splitSubQueries(ES, 3)).hasSize(1);
            assertThat(RerankedContentRetriever.splitSubQueriesWidened(ES, 3))
                    .containsExactly("cuál es el saldo del cliente Pérez", "cuántas órdenes de trabajo están abiertas");
            assertThat(RerankedContentRetriever.splitSubQueriesWidened(EN, 3))
                    .isEqualTo(RerankedContentRetriever.splitSubQueries(EN, 3));
            // A fragment still has to be an information need: "returns" alone is not one.
            assertThat(RerankedContentRetriever.splitSubQueriesWidened(SINGLE, 3))
                    .hasSizeLessThan(2);
        }

        private static final String SECOND_NEED_FR = "bons de travail ouverts";
        private static final String SECOND_NEED_EN = "permission needed read invoice: billing:invoice:view";

        private static List<String> retrieve(String query, List<String> texts, Supplier<QuestionTags> tags) {
            List<Document> candidates = texts.stream().map(Document::new).toList();
            QueryDocumentRetriever delegate = ignored -> candidates;
            return new RerankedContentRetriever(delegate, 3, true, 3, tags)
                    .retrieve(query).stream().map(Document::getText).toList();
        }

        @Test
        @DisplayName(
                "enforced true: the widened split reserves a slot for the second need; heuristic/none: today's cut")
        void enforcedTrueWidensAndReserves() {
            // Three candidates serve the first need and crowd the second need's chunk out of the top-3
            // cut; today's splitter sees no boundary in " et ", so nothing is reserved.
            List<String> texts = List.of(
                    "quel est le solde du client Tremblay aujourd'hui",
                    "le solde du client Tremblay est de 200 dollars",
                    "quel est le solde du client, solde client Tremblay",
                    SECOND_NEED_FR);
            List<String> today = retrieve(FR, texts, QuestionTags::none);
            assertThat(retrieve(FR, texts, () -> HEURISTIC.tag(FR))).isEqualTo(today);
            assertThat(today).doesNotContain(SECOND_NEED_FR);

            List<String> widened = retrieve(FR, texts, () -> enforced(FR, Map.of(TagName.COMPOUND_QUESTION, "true")));
            assertThat(widened).contains(SECOND_NEED_FR);
        }

        @Test
        @DisplayName("enforced false: no split even where today's splitter would split")
        void enforcedFalseSkipsTheSplit() {
            List<String> texts = List.of(
                    "an invoice number looks like INV-2026-000123",
                    "what does an invoice number look like: invoice number example",
                    "invoice number format: what an invoice number looks like",
                    SECOND_NEED_EN);
            assertThat(RerankedContentRetriever.splitSubQueries(EN, 3)).hasSize(2);

            List<String> today = retrieve(EN, texts, QuestionTags::none);
            List<String> gated = retrieve(EN, texts, () -> enforced(EN, Map.of(TagName.COMPOUND_QUESTION, "false")));

            assertThat(today).contains(SECOND_NEED_EN);
            assertThat(gated).doesNotContain(SECOND_NEED_EN);
        }
    }

    @Nested
    @DisplayName("ToolSelectionEngine lookups consumer (ADR-0069 §6 row 3)")
    class Lookups {

        private static final Set<String> CODES = Set.of("AUTHENTICATED", ScopeResolverFixtures.WORKORDER_VIEW);
        private static final Set<String> GATED = Set.of(
                "DateWindowFacadeTool",
                "ExaWebSearchTool",
                "GlossaryFacadeTool",
                "InventoryFacadeTool",
                "OrderFacadeTool",
                "WorkorderFacadeTool");

        /** Stands in for the fixture graph's facade bean; the merge dedupes on the simple class name. */
        static final class WorkorderFacadeTool {}

        private final Object workorderFacade = new WorkorderFacadeTool();

        private final SharedOrchestrationSupport support =
                new SharedOrchestrationSupport(Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC));
        private final InventoryFacadeTool inventoryFacadeTool = new InventoryFacadeTool(
                RestClient.builder(),
                "http://api-gateway",
                "/inventory/v1/inventory/stock/{sku}",
                "/inventory/v1/inventory/search?q={query}",
                "/inventory/v1/inventory/locations/{locationId}/stock",
                "/inventory/v1/inventory/replenishment/policies");
        private final OrderFacadeTool orderFacadeTool = new OrderFacadeTool(
                RestClient.builder(),
                "http://api-gateway",
                "/order/v1/orders/{orderId}",
                "/order/v1/orders/search?q={query}",
                "/order/v1/orders/purchase-orders",
                "/order/v1/orders/purchase-orders/{poId}",
                "/order/v1/orders/purchase-orders/summary");

        private ToolSelectionEngine engine(ScopeGraphProperties properties) {
            ToolRegistryService gating = mock(ToolRegistryService.class);
            when(gating.resolveCandidateSelection(any(ToolSelectionContext.class), anyInt(), any(QuestionTags.class)))
                    .thenReturn(new ToolRegistryService.CandidateSelection(List.of(), GATED, false));
            MasterAgentRegistry registry = mock(MasterAgentRegistry.class);
            when(registry.resolveMasterTools()).thenReturn(List.of());
            when(registry.resolveDomainTools(anyString())).thenReturn(List.of());
            when(registry.resolveToolsByName(any())).thenAnswer(invocation -> {
                List<String> names = invocation.getArgument(0);
                return names.contains("WorkorderFacadeTool") ? List.of(workorderFacade) : List.of();
            });
            ToolSelectionEngine engine = new ToolSelectionEngine(
                    registry,
                    new DateWindowFacadeTool(Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC)),
                    new GlossaryFacadeTool(),
                    new ExaWebSearchTool(RestClient.builder(), "https://api.exa.ai", "", "auto", 5),
                    inventoryFacadeTool,
                    orderFacadeTool,
                    gating,
                    support,
                    8);
            SimpleMeterRegistry meters = new SimpleMeterRegistry();
            engine.setScopeResolver(ScopeResolverFixtures.resolver(properties, meters));
            engine.setScopeConsumers(ScopeResolverFixtures.consumers(properties, meters));
            return engine;
        }

        private List<Object> fallback(ToolSelectionEngine engine, String message) {
            return engine.selectRoleTools("ROLE_USER", CODES, message, HEURISTIC.tag(message))
                    .fallbackTools();
        }

        @Test
        @DisplayName("lookups enforced: the seed entity's lexicon facade replaces the inventory/order keyword tags")
        void lookupsReplaceTheKeywordGuards() {
            String message = "what is the stock of part 4411 on the work order";
            QuestionTags tags = HEURISTIC.tag(message);
            assertThat(tags.aboutInventory()).isTrue();

            ToolSelectionEngine lookups =
                    engine(ScopeResolverFixtures.enforce(60, ScopeGraphProperties.Consumer.LOOKUPS));
            List<Object> added = fallback(lookups, message);

            assertThat(added).contains(workorderFacade);
            assertThat(added).doesNotContain(inventoryFacadeTool, orderFacadeTool);
            assertThat(added).anyMatch(GlossaryFacadeTool.class::isInstance);
        }

        @Test
        @DisplayName("lookups not enforced (shadow; enforce with other consumers): the keyword tags decide as today")
        void withoutLookupsTheTagsDecide() {
            String message = "what is the stock of part 4411 on the work order";
            for (ScopeGraphProperties properties : List.of(
                    ScopeResolverFixtures.shadow(60),
                    ScopeResolverFixtures.enforce(
                            60, ScopeGraphProperties.Consumer.TOOLS, ScopeGraphProperties.Consumer.RAG))) {
                List<Object> added = fallback(engine(properties), message);
                assertThat(added).as(properties.toString()).contains(inventoryFacadeTool);
                assertThat(added).as(properties.toString()).doesNotContain(workorderFacade);
            }
        }

        @Test
        @DisplayName("lookups enforced with no entity in the message: nothing replaces the guards, nothing is added")
        void noSeedAddsNothing() {
            String message = "is sku 4411 in stock at the downtown store?";
            ToolSelectionEngine lookups =
                    engine(ScopeResolverFixtures.enforce(60, ScopeGraphProperties.Consumer.LOOKUPS));

            List<Object> added = fallback(lookups, message);

            assertThat(added).doesNotContain(inventoryFacadeTool, workorderFacade);
        }
    }
}
