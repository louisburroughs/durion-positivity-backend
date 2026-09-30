package com.positivity.mcp.internal.scopegraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.config.StaticRagPreloadProperties;
import com.positivity.mcp.internal.scopegraph.ScopeGraphGlossarySource.GlossaryTerm;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ScopeGraphSourceLoaderTest {

    private final OpenApiSchemaIndexHolder schemaIndexHolder = new OpenApiSchemaIndexHolder();
    private final StaticRagPreloadProperties ragProperties =
            new StaticRagPreloadProperties(ScopeGraphTestFixtures.ragDocs());

    @Test
    @DisplayName(
            "gathers the shipped lexicon, the catalog, the effective RAG docs, the captured index and the glossary")
    void gathersEverySource() {
        schemaIndexHolder.put(OpenApiSchemaIndexBuilder.build(
                ScopeGraphTestFixtures.spec(), "workorder", (path, operation) -> operation.getOperationId()));
        ScopeGraphGlossarySource glossary = () -> List.of(new GlossaryTerm("backed up", List.of()));

        ScopeGraphSources sources = new ScopeGraphSourceLoader(
                        provider(ScopeGraphTestFixtures::catalog), ragProperties, schemaIndexHolder, provider(glossary))
                .load();

        assertThat(sources.lexicon().entities()).isNotEmpty();
        assertThat(sources.catalog()).isEqualTo(ScopeGraphTestFixtures.catalog());
        assertThat(sources.ragDocs()).isEqualTo(ScopeGraphTestFixtures.ragDocs());
        assertThat(sources.schemaIndex().domains()).containsExactly("workorder");
        assertThat(sources.glossaryTerms()).extracting(GlossaryTerm::term).containsExactly("backed up");
    }

    @Test
    @DisplayName("without a glossary source the build simply has no glossary terms")
    void glossaryIsOptional() {
        ScopeGraphSources sources = new ScopeGraphSourceLoader(
                        provider(ScopeGraphTestFixtures::catalog), ragProperties, schemaIndexHolder, provider(null))
                .load();

        assertThat(sources.glossaryTerms()).isEmpty();
        assertThat(sources.schemaIndex().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("without a catalog reader the load fails, which the holder turns into a kept snapshot")
    void catalogReaderIsRequired() {
        ScopeGraphSourceLoader loader =
                new ScopeGraphSourceLoader(provider(null), ragProperties, schemaIndexHolder, provider(null));

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ScopeGraphCatalogReader");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }
}
