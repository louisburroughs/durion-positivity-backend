package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.config.ScopeGraphProperties.Consumer;
import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import com.positivity.mcp.internal.scopegraph.ScopeConsumers;
import com.positivity.mcp.internal.scopegraph.ScopeResolverFixtures;
import com.positivity.mcp.internal.scopegraph.ScopeSet;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/** ADR-0069 §6, RAG consumer: what the request-scoped hook keeps of the fused candidate pool. */
class ScopeRagFilterTest {

    private static final Set<String> CALLER = Set.of("AUTHENTICATED", ScopeResolverFixtures.WORKORDER_VIEW);

    private final RequestScopedUserContext requestContext = new RequestScopedUserContext();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ScopeGraphProperties enforceRag = ScopeResolverFixtures.enforce(60, Consumer.RAG);
    private final ScopeConsumers consumers = ScopeResolverFixtures.consumers(enforceRag, meters);

    /** Chunks from two domains, plus a master one, plus a stray chunk with no metadata at all. */
    private final List<Document> pool = List.of(
            chunk("workorder.status-lifecycle", "workorder", "Workorder lifecycle"),
            chunk("billing.invoices", "billing", "Invoices"),
            chunk("inventory.stock", "inventory", "Stock levels"),
            chunk("glossary", "master", "Glossary"),
            new Document("Untagged"));

    @AfterEach
    void clear() {
        requestContext.clear();
    }

    @Test
    @DisplayName("HIGH: keeps the scope's documents from two domains and every master document, drops the rest")
    void highConfidenceKeepsScopeDocumentsAndMaster() {
        // A work order number seeds HIGH; invoice is reached through billed_by, so billing.invoices
        // (visible to any authenticated caller) is in the scope together with the workorder docs.
        publish("is work order WO-20391 billed yet?");
        assertThat(requestContext.currentScope().orElseThrow().documentIds())
                .contains("workorder.status-lifecycle", "billing.invoices")
                .doesNotContain("inventory.stock");

        List<Document> kept = filter("workorder").retrieve("q");

        assertThat(texts(kept)).containsExactly("Workorder lifecycle", "Invoices", "Glossary");
        assertThat(requestContext.currentScopeRagFilterApplied()).isTrue();
        assertThat(fallbacks()).isZero();
    }

    @Test
    @DisplayName("HIGH: a master agent is narrowed to the scope plus master too")
    void highConfidenceNarrowsTheMasterAgent() {
        publish("work order WO-20391");

        List<Document> kept = filter("master").retrieve("q");

        assertThat(texts(kept)).containsExactly("Workorder lifecycle", "Invoices", "Glossary");
    }

    @Test
    @DisplayName("LOW: re-applies today's eligibility, the agent's scope plus master")
    void lowConfidenceReappliesTodaysEligibility() {
        // "ticket" denotes two entities: an ambiguous term seeds LOW.
        publish("the ticket");
        assertThat(requestContext.currentScope().orElseThrow().confidence()).isEqualTo(ScopeSet.Confidence.LOW);

        List<Document> kept = filter("inventory").retrieve("q");

        assertThat(texts(kept)).containsExactly("Stock levels", "Glossary");
        assertThat(requestContext.currentScopeRagFilterApplied()).isFalse();
        assertThat(fallbacks()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("NONE: a single-domain agent keeps its own scope plus master; a master agent keeps everything")
    void noneConfidenceKeepsTodaysEligibility() {
        publish("hello there");
        assertThat(requestContext.currentScope().orElseThrow().confidence()).isEqualTo(ScopeSet.Confidence.NONE);

        assertThat(texts(filter("billing").retrieve("q"))).containsExactly("Invoices", "Glossary");
        assertThat(filter("master").retrieve("q")).isEqualTo(pool);
        assertThat(requestContext.currentScopeRagFilterApplied()).isFalse();
        assertThat(fallbacks()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("no scope published: today's eligibility, counted as a fallback")
    void noScopeKeepsTodaysEligibility() {
        assertThat(texts(filter("workorder").retrieve("q"))).containsExactly("Workorder lifecycle", "Glossary");
        assertThat(requestContext.currentScopeRagFilterApplied()).isFalse();
        assertThat(fallbacks()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the hook only narrows: it never adds a document the delegate did not return")
    void neverAddsDocuments() {
        publish("work order WO-20391");
        QueryDocumentRetriever empty = query -> List.of();

        assertThat(new ScopeRagFilter(empty, "workorder", requestContext, consumers).retrieve("q"))
                .isEmpty();
    }

    private void publish(String message) {
        ScopeSet scope =
                ScopeResolverFixtures.resolver(enforceRag, meters).resolve(message, CALLER, WorkflowState.IDLE);
        requestContext.recordScope(scope);
    }

    private ScopeRagFilter filter(String agentScope) {
        return new ScopeRagFilter(query -> pool, agentScope, requestContext, consumers);
    }

    private double fallbacks() {
        return meters.get("mcp.scope.fallback").tag("consumer", "rag").counter().count();
    }

    private static Document chunk(String documentId, String ragScope, String text) {
        return new Document(text, Map.of("document_id", documentId, "rag_scope", ragScope));
    }

    private static List<String> texts(List<Document> documents) {
        return documents.stream().map(Document::getText).toList();
    }
}
