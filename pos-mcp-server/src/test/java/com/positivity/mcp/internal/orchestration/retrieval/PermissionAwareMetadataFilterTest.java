package com.positivity.mcp.internal.orchestration.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/** Gate 5 G5.1: permission-aware RAG visibility filtering. */
class PermissionAwareMetadataFilterTest {

    private static Document doc(String text, String requiredPermissionsCsv) {
        Map<String, Object> metadata =
                requiredPermissionsCsv == null ? Map.of() : Map.of("required_permissions", requiredPermissionsCsv);
        return new Document(text, metadata);
    }

    private static QueryDocumentRetriever delegateOf(Document... docs) {
        List<Document> list = List.of(docs);
        return ignored -> list;
    }

    private List<String> texts(List<Document> contents) {
        return contents.stream().map(Document::getText).toList();
    }

    @Test
    @DisplayName("public doc (no required_permissions) is visible to anyone, incl. empty codes")
    void publicVisible() {
        var filter = new PermissionAwareMetadataFilter(delegateOf(doc("public", null)), Set.of());
        assertThat(texts(filter.retrieve("q"))).containsExactly("public");
    }

    @Test
    @DisplayName("AUTHENTICATED doc is visible to an authenticated caller (carries the sentinel), hidden otherwise")
    void authenticatedSentinel() {
        QueryDocumentRetriever delegate = delegateOf(doc("authn", "AUTHENTICATED"));
        // Authenticated callers carry the synthetic AUTHENTICATED code (CurrentUserContext).
        var authed = new PermissionAwareMetadataFilter(delegate, Set.of("AUTHENTICATED"));
        assertThat(texts(authed.retrieve("q"))).containsExactly("authn");
        // A context with no AUTHENTICATED sentinel does not see it (fail-closed).
        var unauthed = new PermissionAwareMetadataFilter(delegate, Set.of("workorder:workorder:view"));
        assertThat(unauthed.retrieve("q")).isEmpty();
    }

    @Test
    @DisplayName("restricted doc visible only when caller holds a required code")
    void restrictedRequiresCode() {
        QueryDocumentRetriever delegate = delegateOf(doc("admin-only", "security:permission:view"));

        var withCode = new PermissionAwareMetadataFilter(delegate, Set.of("security:permission:view", "x"));
        assertThat(texts(withCode.retrieve("q"))).containsExactly("admin-only");

        var withoutCode = new PermissionAwareMetadataFilter(delegate, Set.of("workorder:workorder:view"));
        assertThat(withoutCode.retrieve("q")).isEmpty();
    }

    @Test
    @DisplayName("multi-required: any one matching code grants visibility; mixed set filtered per-doc")
    void multiRequiredAndMixed() {
        QueryDocumentRetriever delegate = delegateOf(
                doc("public", null), doc("crm", "crm:party:view,crm:party:search"), doc("acct", "accounting:je:view"));
        var filter = new PermissionAwareMetadataFilter(delegate, Set.of("crm:party:search"));

        // public + crm (matched on one of the two), but not acct
        assertThat(texts(filter.retrieve("q"))).containsExactly("public", "crm");
    }
}
