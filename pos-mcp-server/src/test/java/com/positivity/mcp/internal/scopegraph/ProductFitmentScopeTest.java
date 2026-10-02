package com.positivity.mcp.internal.scopegraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.config.StaticRagPreloadProperties.StaticDocEntry;
import com.positivity.mcp.internal.scopegraph.ScopeSet.Seed;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #2381: {@code product} was the one lexicon entity with no RAG document in scope. The tagging
 * bake-off seeded it 22 times and 10 of those turns reached no document, and the fitment questions
 * ("which brake pads fit a 2018 Silverado?") seeded no entity at all. This pins the three parts of
 * the fix against the shipped configuration: the fitment and part-kind vocabulary of {@code
 * scope-graph/entities.yaml}, the {@code catalog.products-fitment} preload entry in both profiles,
 * and the graph edges that put the guide and the fitment filter one hop from a {@code product} seed.
 */
class ProductFitmentScopeTest {

    private static final String DOC_ID = "catalog.products-fitment";
    private static final String FILTER_TOOL = "vehicle-fitment_filterproductsbyvehicleattributes";
    private static final Set<String> DOC_PERMISSIONS = Set.of("catalog:product:view", "vehicle-fitment:catalog:view");

    private static final LexiconLookup LOOKUP = new LexiconLookup(EntityLexiconLoader.loadDefault());

    /** The issue's three bake-off messages (en-0146, en-0083, en-0253) and their fixture translations. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "Show me rotors and brake pads for a 2019 Civic",
                "Which brake pads fit a 2018 Silverado?",
                "Look up product BRK-9920 in the catalog",
                "Montre-moi les rotors et les plaquettes pour une Civic 2019",
                "Quelles plaquettes de frein vont sur une Silverado 2018?",
                "Cherche le produit BRK-9920 au catalogue",
                "Muéstrame rotores y balatas para un Civic 2019",
                "¿Qué balatas le quedan a una Silverado 2018?",
                "Busca el producto BRK-9920 en el catálogo"
            })
    @DisplayName("the bake-off product and fitment messages seed product in en, fr-CA and es")
    void bakeOffMessagesSeedProduct(String message) {
        assertThat(seededEntities(message)).contains("product");
    }

    /** Fitment vocabulary with no part noun: it is the fitment terms alone that seed. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "What fits a 2020 Camry?",
                "Show the fitment for this part",
                "Is it compatible with a 2017 F-150?",
                "Search by year/make/model",
                "Vérifie la compatibilité avec une Corolla 2016",
                "Revisa la compatibilidad con un Jetta 2015"
            })
    @DisplayName("fitment phrasing without a part name seeds product")
    void fitmentVocabularySeedsProduct(String message) {
        assertThat(seededEntities(message)).contains("product");
    }

    /** Part kinds named after the seeded catalog subcategories, as the gate fixtures phrase them. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "Do we also carry wiper blades?",
                "Create a purchase order for 10 oil filters.",
                "Est-ce qu'on vend aussi des essuie-glaces?",
                "Remets en stock les filtres à huile retournés",
                "¿Ofrecemos también plumillas limpiaparabrisas?",
                "Necesito pedir 6 juegos de plumillas al proveedor"
            })
    @DisplayName("part kinds from the catalog taxonomy seed product")
    void partKindsSeedProduct(String message) {
        assertThat(seededEntities(message)).contains("product");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "Can you fit Mr. Smith in on Tuesday afternoon?",
                "We can fit two more appointments tomorrow",
                "What's the price of a tire rotation?",
                "What's the make and model on this customer's vehicle?",
                "List the open workorders at this location",
                "Peux-tu ajouter un rendez-vous mardi?",
                "¿Cuál es el saldo pendiente de la factura?"
            })
    @DisplayName("scheduling 'fit', vehicle questions and unrelated messages do not seed product")
    void unrelatedMessagesDoNotSeedProduct(String message) {
        assertThat(seededEntities(message)).doesNotContain("product");
    }

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"default", "alpha"})
    @DisplayName(
            "the products and fitment guide is preloaded in the inventory scope, about product, behind the read codes")
    void guideIsPreloaded(String profile) {
        List<StaticDocEntry> docs = ScopeGraphRealConfigValidationTest.ragDocs(profile);

        StaticDocEntry guide = docs.stream()
                .filter(doc -> DOC_ID.equals(doc.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(DOC_ID + " missing from the " + profile + " preload list"));
        assertThat(guide.sourcePath()).isEqualTo("classpath:rag/catalog-products-fitment-guide.md");
        assertThat(guide.ragScope()).isEqualTo("inventory");
        assertThat(guide.entities()).containsExactly("product");
        assertThat(Set.copyOf(guide.requiredPermissions())).isEqualTo(DOC_PERMISSIONS);
        assertThat(docs)
                .as("%s: at least one preload document explains product", profile)
                .anyMatch(doc -> doc.entities().contains("product"));
    }

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"default", "alpha"})
    @DisplayName("in the real graph the guide is about product and the fitment filter acts on product")
    void graphLinksGuideAndFitmentFilterToProduct(String profile) {
        ScopeGraph graph = ScopeGraphRealConfigValidationTest.build(profile).graph();
        NodeId product = NodeId.of(NodeType.ENTITY, "product");

        assertThat(graph.outgoing(NodeId.of(NodeType.RAG_DOC, DOC_ID), EdgeType.ABOUT))
                .extracting(edge -> edge.to())
                .contains(product);
        assertThat(graph.outgoing(NodeId.of(NodeType.TOOL, FILTER_TOOL), EdgeType.ACTS_ON))
                .as("the product-by-vehicle filter is a product tool, not only a vehicle one")
                .extracting(edge -> edge.to())
                .contains(product);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Which brake pads fit a 2018 Silverado?", "Which brake pad fits a 2018 Silverado?"})
    @DisplayName("a fitment question resolves a scope holding the guide for a caller with the catalog read code")
    void fitmentQuestionReachesTheGuide(String message) {
        ScopeGraph graph = ScopeGraphRealConfigValidationTest.build("default").graph();

        ScopeSet scope = resolve(graph, message, Set.of("catalog:product:view"));

        assertThat(scope.seeds()).extracting(Seed::entity).contains("product");
        assertThat(scope.documentIds()).contains(DOC_ID);
    }

    @Test
    @DisplayName("the singular, exact fitment terms give a HIGH scope; the plural is folded and stays LOW")
    void exactFitmentTermsAreHighConfidence() {
        ScopeGraph graph = ScopeGraphRealConfigValidationTest.build("default").graph();

        // ADR-0069 §5.4: only an identifier or an unambiguous exact term is HIGH, so a plural part name
        // (a folded match) leaves the rag consumer on its fallback while still naming the guide.
        assertThat(resolve(graph, "Which brake pad fits a 2018 Silverado?", DOC_PERMISSIONS)
                        .confidence())
                .isEqualTo(ScopeSet.Confidence.HIGH);
        assertThat(resolve(graph, "Which brake pads fit a 2018 Silverado?", DOC_PERMISSIONS)
                        .confidence())
                .isEqualTo(ScopeSet.Confidence.LOW);
    }

    @Test
    @DisplayName("a caller with neither read code does not get the guide in scope")
    void callerWithoutReadCodesDoesNotSeeTheGuide() {
        ScopeGraph graph = ScopeGraphRealConfigValidationTest.build("default").graph();

        assertThat(resolve(graph, "Which brake pad fits a 2018 Silverado?", Set.of("workorder:workorder:view"))
                        .documentIds())
                .doesNotContain(DOC_ID);
    }

    private static ScopeSet resolve(ScopeGraph graph, String message, Set<String> permissions) {
        return ScopeResolver.resolve(graph, TermMatcher.of(graph), message, permissions, "IDLE", 60);
    }

    private static List<String> seededEntities(String message) {
        return LOOKUP.seeds(message).stream().map(Seed::entity).toList();
    }
}
