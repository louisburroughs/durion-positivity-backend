package com.positivity.mcp.internal.scopegraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.config.StaticRagPreloadProperties.StaticDocEntry;
import com.positivity.mcp.internal.scopegraph.EntityLexicon.EntityDefinition;
import com.positivity.mcp.internal.scopegraph.EntityLexicon.FacadeToolRef;
import com.positivity.mcp.internal.scopegraph.ScopeSet.Seed;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #2384: the reporting and analytics vocabulary (revenue, sales, margin, profit, spend, period
 * comparisons) resolves to the {@code sales-report} entity, which a document in both preload lists
 * explains. Built on the real lexicon, preload lists, specs, seed and glossary, the same graph the
 * real-config validation builds.
 */
class SalesReportEntityTest {

    private static final String ENTITY = "sales-report";
    private static final String DOCUMENT = "accounting.sales-analytics";
    private static final List<String> DOCUMENT_PERMISSIONS =
            List.of("reporting:view:financial-statements", "accounting:analytics:view", "invoice:analytics:view");

    private static ScopeGraph graph;
    private static TermMatcher graphMatcher;
    private static TermMatcher lexiconMatcher;

    @BeforeAll
    static void buildRealGraph() {
        graph = ScopeGraphRealConfigValidationTest.build("default").graph();
        graphMatcher = TermMatcher.of(graph);
        lexiconMatcher = TermMatcher.of(EntityLexiconLoader.loadDefault());
    }

    /** The six bake-off turns of #2384 that resolved no entity, their gate fr-CA and es translations, and other quarter comparisons. */
    static Stream<String> analyticsMessages() {
        return Stream.of(
                // en-0100, en-0097, en-0094, en-0093, en-0091, en-0102
                "What's our gross margin?",
                "Show sales trend for the past 12 weeks",
                "How did Q3 compare to Q2?",
                "What did we spend with Michelin in 2025?",
                "Show revenue by month for the last six months",
                "What was the profit on tire sales between March 1 and March 31?",
                // fr-CA
                "Quelle est notre marge brute?",
                "Montre la tendance des ventes des 12 dernières semaines",
                "Comment le T3 se compare-t-il au T2?",
                "Combien avons-nous dépensé chez Michelin en 2025?",
                "Montre le revenu par mois pour les six derniers mois",
                "Quel a été le profit sur la vente de pneus entre le 1er et le 31 mars?",
                // es
                "¿Cuál es nuestro margen bruto?",
                "Muestra la tendencia de ventas de las últimas 12 semanas",
                "¿Cómo le fue al T3 frente al T2?",
                "¿Cuánto gastamos con Michelin en 2025?",
                "Muestra el ingreso por mes de los últimos seis meses",
                "¿Cuál fue la utilidad por venta de llantas entre el 1 y el 31 de marzo?",
                // Other comparison wordings of quarters.
                "Q4 vs Q3, please",
                "Compare Q3 and Q2",
                "Le T4 par rapport au T3",
                "El T4 comparado con el T3");
    }

    /** Messages that share a word with the analytics vocabulary but are about something else. */
    static Stream<String> nonAnalyticsMessages() {
        return Stream.of(
                // "sales" inside a longer tax or order term is that term, not the analytics entity.
                "What's the sales tax rate in Ontario?",
                "Quel est le taux de taxe de vente en Ontario?",
                "¿Cuál es la tasa del impuesto sobre ventas en Ontario?",
                "Cancel sales order ORD-5530",
                "Annule la commande de vente ORD-5530",
                "Cancela la orden de venta ORD-5530",
                // The catalog's price-override guardrail margin is a pricing concept.
                "The override is below the minimum margin for this store",
                "Le prix dérogatoire est sous la marge minimale",
                "El precio queda por debajo del margen mínimo",
                // A quarter named without a comparison is a period, not a money figure (#2391 review).
                "How many work orders were completed in Q3?",
                "Combien de bons de travail ont été terminés au T3?",
                "¿Cuántas órdenes de trabajo se completaron en el T3?",
                "Which appointments are booked for Q4?",
                // Plain operational and purchasing questions.
                "How many brake pads are on hand?",
                "Create a purchase order for Michelin tires",
                "Show the income statement for last quarter");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyticsMessages")
    @DisplayName("each bake-off analytics turn (en, fr-CA, es) seeds sales-report on the real graph")
    void analyticsTurnSeedsTheEntity(String message) {
        assertThat(seededEntities(graphMatcher, message)).contains(ENTITY);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonAnalyticsMessages")
    @DisplayName("a message that only shares a word with the analytics vocabulary does not seed sales-report")
    void nonAnalyticsTurnDoesNotSeedTheEntity(String message) {
        assertThat(seededEntities(graphMatcher, message)).doesNotContain(ENTITY);
    }

    @Test
    @DisplayName("the longer tax, order and statement terms keep their own seed beside the new vocabulary")
    void collidingTermsKeepTheirEntity() {
        assertThat(seededEntities(graphMatcher, "What's the sales tax rate in Ontario?"))
                .containsExactly("tax");
        assertThat(seededEntities(graphMatcher, "Cancel sales order ORD-5530")).containsExactly("order");
        assertThat(seededEntities(graphMatcher, "Show the income statement for last quarter"))
                .containsExactly("financial-report");
        // A message naming both keeps both: the supplier seed is not shadowed by the spend vocabulary.
        assertThat(seededEntities(graphMatcher, "What is our largest vendor by spend?"))
                .containsExactlyInAnyOrder("supplier", ENTITY);
    }

    @Test
    @DisplayName("a lexicon term seeds HIGH; a glossary phrase around the term seeds LOW as GLOSSARY_TERM")
    void matchKinds() {
        assertThat(graphMatcher.match("What's our gross margin?"))
                .containsExactly(new Seed(ENTITY, MatchKind.EXACT_TERM));
        assertThat(graphMatcher.match("How did Q3 compare to Q2?"))
                .containsExactly(new Seed(ENTITY, MatchKind.EXACT_TERM));
        // "what did we spend with" is a BusinessGlossary phrase containing the lexicon term "spend": the
        // longer glossary match stands, as for "best customers" -> customer.
        assertThat(graphMatcher.match("What did we spend with Michelin in 2025?"))
                .containsExactly(new Seed(ENTITY, MatchKind.GLOSSARY_TERM));
        // The heuristic tagger's lexicon-only matcher has no glossary phrases: the term itself seeds.
        assertThat(lexiconMatcher.match("What did we spend with Michelin in 2025?"))
                .containsExactly(new Seed(ENTITY, MatchKind.EXACT_TERM));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyticsMessages")
    @DisplayName("the heuristic tagger's lexicon lookup seeds sales-report for the same turns")
    void lexiconLookupSeedsTheEntity(String message) {
        assertThat(seededEntities(lexiconMatcher, message)).contains(ENTITY);
    }

    @Test
    @DisplayName("BusinessGlossary phrases that name spend or revenue denote sales-report, beside their other entities")
    void glossaryPhrasesLinkToTheEntity() {
        assertThat(denoted("what did we spend with")).containsExactly(ENTITY);
        assertThat(denoted("how much did we spend with")).containsExactly(ENTITY);
        assertThat(denoted("vendor spend")).containsExactlyInAnyOrder(ENTITY, "supplier");
        assertThat(denoted("spend with vendor")).containsExactlyInAnyOrder(ENTITY, "supplier");
        assertThat(denoted("top customers by revenue")).containsExactlyInAnyOrder(ENTITY, "customer");
        // A glossary phrase that names no analytics term is untouched.
        assertThat(denoted("best customers")).containsExactly("customer");
        assertThat(denoted("who owes us the most money")).isEmpty();
    }

    @Test
    @DisplayName("the entity is accounting-owned, carries en/fr/es terms and reads through the three report facades")
    void lexiconEntry() {
        EntityDefinition entity = EntityLexiconLoader.loadDefault().entities().stream()
                .filter(definition -> definition.key().equals(ENTITY))
                .findFirst()
                .orElseThrow();

        // Money figures are accounting (domains: sentence for reporting), not operational reporting.
        assertThat(entity.domain()).isEqualTo("accounting");
        assertThat(entity.terms()).containsKeys("en", "fr", "es");
        assertThat(entity.facadeTools())
                .extracting(FacadeToolRef::tool)
                .containsExactlyInAnyOrder("ReportingFacadeTool", "AccountingFacadeTool", "InvoiceFacadeTool");
        assertThat(entity.facadeTools()).allMatch(ref -> ref.access() == Access.READS);
        // Deliberate omissions: the pricing guardrail margin, operational trends, and the phrases that
        // would shadow the customer or supplier seed.
        Set<String> terms =
                entity.terms().values().stream().flatMap(List::stream).collect(Collectors.toSet());
        assertThat(terms).doesNotContain("margin", "marge", "margen", "trend", "top customer", "vendor spend");
        // A bare quarter label is a period; only comparison phrases name the business's results.
        assertThat(terms).doesNotContain("q1", "q2", "q3", "q4", "t1", "t2", "t3", "t4");
        assertThat(terms).contains("compare to q2", "vs q2", "se compare-t-il au t2", "frente al t2");
    }

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"default", "alpha"})
    @DisplayName("the sales analytics document is preloaded in both profiles with its scope, permissions and entities")
    void documentEntry(String profile) {
        StaticDocEntry entry = ScopeGraphRealConfigValidationTest.ragDocs(profile).stream()
                .filter(doc -> doc.id().equals(DOCUMENT))
                .findFirst()
                .orElseThrow(() -> new AssertionError(DOCUMENT + " missing under profile " + profile));

        assertThat(entry.sourcePath()).isEqualTo("classpath:rag/sales-analytics-guide.md");
        assertThat(entry.ragScope()).isEqualTo("accounting");
        assertThat(entry.requiredPermissions()).containsExactlyElementsOf(DOCUMENT_PERMISSIONS);
        assertThat(entry.entities()).containsExactly(ENTITY, "financial-report", "customer", "supplier");
    }

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"default", "alpha"})
    @DisplayName("sales-report is covered by at least one document in both profiles")
    void entityIsCoveredByADocument(String profile) {
        ScopeGraph profileGraph =
                ScopeGraphRealConfigValidationTest.build(profile).graph();
        Set<String> documents = new TreeSet<>();
        profileGraph
                .incoming(NodeId.of(NodeType.ENTITY, ENTITY), EdgeType.ABOUT)
                .forEach(edge -> documents.add(edge.from().key()));

        assertThat(documents).contains(DOCUMENT);
    }

    @Test
    @DisplayName(
            "a caller holding any one of the document's permissions gets it in scope; a caller holding none does not")
    void resolvedScopeCarriesTheDocument() {
        for (String permission : DOCUMENT_PERMISSIONS) {
            ScopeSet scope = resolve("Show revenue by month for the last six months", Set.of(permission));
            assertThat(scope.confidence()).isEqualTo(ScopeSet.Confidence.HIGH);
            assertThat(scope.documentIds()).as("caller holding %s", permission).contains(DOCUMENT);
        }
        assertThat(resolve("Show revenue by month for the last six months", Set.of("crm:party:view"))
                        .documentIds())
                .doesNotContain(DOCUMENT);
    }

    private static ScopeSet resolve(String message, Set<String> permissions) {
        return ScopeResolver.resolve(graph, graphMatcher, message, permissions, "IDLE", 60);
    }

    private static Set<String> seededEntities(TermMatcher matcher, String message) {
        return matcher.match(message).stream().map(Seed::entity).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> denoted(String glossaryPhrase) {
        NodeId term = NodeId.of(NodeType.TERM, ScopeGraphBuilder.termKey("en", glossaryPhrase));
        assertThat(graph.contains(term)).as("glossary term node %s", term).isTrue();
        return graph.outgoing(term, EdgeType.DENOTES).stream()
                .map(edge -> edge.to().key())
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
