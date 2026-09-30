package com.positivity.mcp.internal.scopegraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.config.StaticRagPreloadProperties.StaticDocEntry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-0069 section 3: a profile list of {@code mcp.rag.preload.docs} replaces the base list wholesale,
 * so {@code application.yml} and {@code application-alpha.yml} must carry the same documents with the
 * same scope, permissions and {@code entities:}. Drift between them would make the scope graph, and
 * the RAG corpus itself, differ silently between environments.
 */
class RagPreloadProfileParityTest {

    @Test
    @DisplayName("the default and alpha preload lists are equal entry for entry")
    void alphaListEqualsDefaultList() {
        List<StaticDocEntry> base = ScopeGraphRealConfigValidationTest.ragDocs("default");
        List<StaticDocEntry> alpha = ScopeGraphRealConfigValidationTest.ragDocs("alpha");

        assertThat(alpha)
                .as("alpha mcp.rag.preload.docs must list the same documents, in the same order, as the default")
                .hasSameSizeAs(base);
        for (int i = 0; i < base.size(); i++) {
            StaticDocEntry expected = base.get(i);
            StaticDocEntry actual = alpha.get(i);
            assertThat(actual.id()).as("id of entry %d", i).isEqualTo(expected.id());
            assertThat(actual.sourcePath())
                    .as("source-path of %s", expected.id())
                    .isEqualTo(expected.sourcePath());
            assertThat(actual.ragScope()).as("rag-scope of %s", expected.id()).isEqualTo(expected.ragScope());
            assertThat(actual.requiredPermissions())
                    .as("required-permissions of %s", expected.id())
                    .isEqualTo(expected.requiredPermissions());
            assertThat(actual.entities()).as("entities of %s", expected.id()).isEqualTo(expected.entities());
        }
    }

    @Test
    @DisplayName("every entry declares entities, and none is listed twice")
    void everyEntryDeclaresEntitiesAndIdsAreUnique() {
        for (String profile : List.of("default", "alpha")) {
            List<StaticDocEntry> docs = ScopeGraphRealConfigValidationTest.ragDocs(profile);

            assertThat(docs).as("%s preload list", profile).isNotEmpty();
            assertThat(docs)
                    .as("%s entries without entities:", profile)
                    .allSatisfy(doc -> assertThat(doc.entities()).isNotEmpty());
            assertThat(docs.stream().map(StaticDocEntry::id).distinct().count())
                    .as("%s distinct document ids", profile)
                    .isEqualTo(docs.size());
        }
    }
}
