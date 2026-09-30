package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.scopegraph.ScopeGraphGlossarySource.GlossaryTerm;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BusinessGlossaryScopeTermsTest {

    @Test
    @DisplayName("every glossary definition is handed over as its term and aliases, nothing else")
    void exposesEveryDefinition() {
        List<GlossaryTerm> terms = new BusinessGlossaryScopeTerms().terms();

        assertThat(terms).hasSameSizeAs(BusinessGlossary.definitions());
        assertThat(terms)
                .extracting(GlossaryTerm::term)
                .containsExactlyElementsOf(BusinessGlossary.definitions().stream()
                        .map(BusinessGlossary.Definition::term)
                        .toList());
        assertThat(terms).allSatisfy(term -> assertThat(term.aliases()).isSorted());
        assertThat(terms.stream().mapToInt(term -> term.aliases().size()).sum())
                .isEqualTo(BusinessGlossary.definitions().stream()
                        .mapToInt(definition -> definition.aliases().size())
                        .sum());
    }
}
