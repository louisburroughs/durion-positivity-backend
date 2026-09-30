package com.positivity.mcp.internal.scopegraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.config.ScopeGraphProperties.Consumer;
import com.positivity.mcp.internal.domain.WorkflowState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-0069 §7 (spec §2.10): what the scope card says, and what it never says. */
class ScopeCardRendererTest {

    /** A message with a nonce no lexicon term could produce, so its absence from the card is meaningful. */
    private static final String MESSAGE = "is work order WO-20391 for zebulon-ostrich-4471 billed to the customer yet?";

    private static final Set<String> WORKORDER_VIEWER = Set.of("AUTHENTICATED", ScopeResolverFixtures.WORKORDER_VIEW);
    private static final Set<String> AUTHENTICATED_ONLY = Set.of("AUTHENTICATED");

    private final ScopeGraph graph = ScopeResolverFixtures.graph();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    @DisplayName("renders the spec's sections, in order, from graph definitions only")
    void rendersTheSpecFormat() {
        String card = render(MESSAGE, WORKORDER_VIEWER, 400).orElseThrow();

        List<String> lines = card.lines().toList();
        assertThat(lines.getFirst()).isEqualTo(ScopeCardRenderer.HEADER);
        assertThat(lines.get(1))
                .isEqualTo(
                        "Entities: workorder (domain workorder), estimate (domain workorder), invoice (domain billing)");
        assertThat(lines.get(2)).isEqualTo("Relations: estimate promotes_to workorder, workorder billed_by invoice");
        assertThat(lines.get(3)).isEqualTo("States: workorder: COMPLETED, DRAFT");
        // Slot order: hop, then reads before writes, then name. The facade writes estimate, so it is last.
        assertThat(lines.get(4))
                .isEqualTo("Actions: workorder_getworkorder reads workorder [requires workorder:workorder:view]");
        assertThat(lines.get(5))
                .isEqualTo("  workorder_listworkorders reads workorder [requires workorder:workorder:view]");
        assertThat(lines.get(6))
                .isEqualTo(
                        "  WorkorderFacadeTool reads workorder; writes estimate [requires workorder:workorder:view]");
        assertThat(lines.get(7)).isEqualTo("Screens: Work Orders -> /workorders");
        assertThat(lines.get(8)).isEqualTo("  Work In Progress -> /workorders/wip");
        assertThat(lines).hasSize(9);
    }

    @Test
    @DisplayName("never echoes the message, a term or an identifier pattern")
    void neverEchoesUserTextOrTerms() {
        String card = render(MESSAGE, WORKORDER_VIEWER, 400).orElseThrow();

        assertThat(card).doesNotContain("zebulon", "4471", "WO-20391", "billed to the customer yet");
        // Terms and patterns of the graph: the French/Spanish phrases and the regex itself.
        assertThat(card).doesNotContain("bon de travail", "orden de trabajo", "\\\\bWO-", "work order");
    }

    @Test
    @DisplayName("names no node the caller lacks permission for, and prints only codes the caller holds")
    void namesNoUnpermittedNode() {
        String card = render(MESSAGE, WORKORDER_VIEWER, 400).orElseThrow();

        // InvoiceFacadeTool needs billing:invoice:view; billing.secret needs billing:admin; the
        // Work Orders screen needs workorder:workorder:view (held). None of the unheld ones appear.
        assertThat(card)
                .doesNotContain("InvoiceFacadeTool", "billing:invoice:view", "billing:admin", "NoRowsFacadeTool");
        // getLaborAnalytics is a group the caller does not satisfy: its codes are not printed.
        assertThat(card).doesNotContain("workorder:analytics:view", "location:read");
    }

    @Test
    @DisplayName("an entity, relation or state appears only when a permitted Tool, RagDoc or Screen connects to it")
    void requiresAConnectingPermittedNode() {
        // AUTHENTICATED only: no tool is permitted. workorder is connected by its public document and
        // the unguarded screen, invoice by billing.invoices (AUTHENTICATED); estimate by nothing.
        String card = render(MESSAGE, AUTHENTICATED_ONLY, 400).orElseThrow();

        assertThat(card)
                .contains("Entities: workorder (domain workorder), invoice (domain billing)")
                .contains("Relations: workorder billed_by invoice")
                .contains("States: workorder: COMPLETED, DRAFT")
                .contains("Screens: Work In Progress -> /workorders/wip")
                .doesNotContain("estimate", "promotes_to", "Actions:", "Work Orders ->", "requires");
    }

    @Test
    @DisplayName("nothing qualifies: no card")
    void noQualifyingEntityNoCard() {
        // "customer" seeds customer, which nothing connects to, and reaches invoice, whose every
        // connector needs a code (AUTHENTICATED included). A caller with no codes qualifies nothing.
        assertThat(render("the customer", Set.of(), 400)).isEmpty();
    }

    @Test
    @DisplayName("the budget drops whole lines from the end; below the entities line there is no card")
    void budgetDropsWholeLinesFromTheEnd() {
        String full = render(MESSAGE, WORKORDER_VIEWER, 400).orElseThrow();
        int fullTokens = ScopeCardRenderer.estimateTokens(full);
        String twoLines =
                ScopeCardRenderer.HEADER + "\n" + full.lines().toList().get(1);
        int twoLineTokens = ScopeCardRenderer.estimateTokens(twoLines);
        assertThat(twoLineTokens).isLessThan(fullTokens);

        String cut = render(MESSAGE, WORKORDER_VIEWER, twoLineTokens).orElseThrow();
        assertThat(cut).isEqualTo(twoLines);
        assertThat(ScopeCardRenderer.estimateTokens(cut)).isLessThanOrEqualTo(twoLineTokens);

        assertThat(render(MESSAGE, WORKORDER_VIEWER, twoLineTokens - 1)).isEmpty();
        assertThat(ScopeCardRenderer.estimateTokens(
                        render(MESSAGE, WORKORDER_VIEWER, fullTokens - 1).orElseThrow()))
                .isLessThan(fullTokens);
    }

    @Test
    @DisplayName("ScopeConsumers renders only when the card is enforced and the confidence is HIGH")
    void consumersGateOnEnforcementAndConfidence() {
        ScopeGraphProperties enforced = ScopeResolverFixtures.enforce(60, Consumer.CARD);
        ScopeConsumers card = ScopeResolverFixtures.consumers(enforced, meters);
        ScopeResolver resolver = ScopeResolverFixtures.resolver(enforced, meters);

        assertThat(card.renderCard(resolver.resolve(MESSAGE, WORKORDER_VIEWER, WorkflowState.IDLE), WORKORDER_VIEWER))
                .isPresent();
        assertThat(fallbacks()).isZero();
        // LOW (ambiguous "ticket") and NONE: no card, one fallback each.
        assertThat(card.renderCard(
                        resolver.resolve("open the ticket", WORKORDER_VIEWER, WorkflowState.IDLE), WORKORDER_VIEWER))
                .isEmpty();
        assertThat(card.renderCard(resolver.resolve("hello", WORKORDER_VIEWER, WorkflowState.IDLE), WORKORDER_VIEWER))
                .isEmpty();
        assertThat(card.renderCard(null, WORKORDER_VIEWER)).isEmpty();
        assertThat(fallbacks()).isEqualTo(3.0);

        // Not enforced (shadow, or enforce without the card listed): nothing, and no fallback either.
        for (ScopeGraphProperties properties : List.of(
                ScopeResolverFixtures.shadow(60), ScopeResolverFixtures.enforce(60, Consumer.RAG, Consumer.TOOLS))) {
            SimpleMeterRegistry own = new SimpleMeterRegistry();
            ScopeConsumers silent = ScopeResolverFixtures.consumers(properties, own);
            assertThat(silent.renderCard(
                            ScopeResolverFixtures.resolver(properties, own)
                                    .resolve(MESSAGE, WORKORDER_VIEWER, WorkflowState.IDLE),
                            WORKORDER_VIEWER))
                    .isEmpty();
            assertThat(own.find("mcp.scope.fallback").counters().stream()
                            .mapToDouble(counter -> counter.count())
                            .sum())
                    .isZero();
        }
    }

    private Optional<String> render(String message, Set<String> callerCodes, int budget) {
        ScopeSet scope = ScopeResolverFixtures.resolver(ScopeResolverFixtures.shadow(60), meters)
                .resolve(message, callerCodes, WorkflowState.IDLE);
        assertThat(scope.confidence()).isEqualTo(ScopeSet.Confidence.HIGH);
        return ScopeCardRenderer.render(scope, graph, callerCodes, budget);
    }

    private double fallbacks() {
        return meters.get("mcp.scope.fallback")
                .tag("consumer", "card")
                .counter()
                .count();
    }
}
