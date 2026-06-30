package com.positivity.mcp.internal.orchestration.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Gate 5 G5.1: permission-aware RAG visibility filtering. */
class PermissionAwareMetadataFilterTest {

    private static Content doc(String text, String requiredPermissionsCsv) {
        Metadata md = requiredPermissionsCsv == null
                ? new Metadata()
                : Metadata.from(Map.of("required_permissions", requiredPermissionsCsv));
        return Content.from(TextSegment.from(text, md));
    }

    private static ContentRetriever delegateOf(Content... docs) {
        List<Content> list = List.of(docs);
        return query -> list;
    }

    private List<String> texts(List<Content> contents) {
        return contents.stream().map(c -> c.textSegment().text()).toList();
    }

    @Test
    @DisplayName("public doc (no required_permissions) is visible to anyone, incl. empty codes")
    void publicVisible() {
        var filter = new PermissionAwareMetadataFilter(delegateOf(doc("public", null)), Set.of());
        assertThat(texts(filter.retrieve(Query.from("q")))).containsExactly("public");
    }

    @Test
    @DisplayName("AUTHENTICATED doc is visible to any authenticated caller")
    void authenticatedSentinel() {
        var filter = new PermissionAwareMetadataFilter(delegateOf(doc("authn", "AUTHENTICATED")), Set.of());
        assertThat(texts(filter.retrieve(Query.from("q")))).containsExactly("authn");
    }

    @Test
    @DisplayName("restricted doc visible only when caller holds a required code")
    void restrictedRequiresCode() {
        ContentRetriever delegate = delegateOf(doc("admin-only", "security:permission:view"));

        var withCode = new PermissionAwareMetadataFilter(delegate, Set.of("security:permission:view", "x"));
        assertThat(texts(withCode.retrieve(Query.from("q")))).containsExactly("admin-only");

        var withoutCode = new PermissionAwareMetadataFilter(delegate, Set.of("workorder:workorder:view"));
        assertThat(withoutCode.retrieve(Query.from("q"))).isEmpty();
    }

    @Test
    @DisplayName("multi-required: any one matching code grants visibility; mixed set filtered per-doc")
    void multiRequiredAndMixed() {
        ContentRetriever delegate = delegateOf(
                doc("public", null), doc("crm", "crm:party:view,crm:party:search"), doc("acct", "accounting:je:view"));
        var filter = new PermissionAwareMetadataFilter(delegate, Set.of("crm:party:search"));

        // public + crm (matched on one of the two), but not acct
        assertThat(texts(filter.retrieve(Query.from("q")))).containsExactly("public", "crm");
    }
}
