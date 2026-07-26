package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.domain.ToolPrerequisite;
import com.positivity.mcp.internal.repository.ToolPrerequisiteRepository;
import com.positivity.mcp.internal.service.PrerequisiteResolver.MissingParam;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("PrerequisiteResolverImpl — one-hop missing-param resolution (rung 2)")
class PrerequisiteResolverImplTest {

    private static final ToolPrerequisite LOCATION_EDGE =
            new ToolPrerequisite("workorder_listwip", "locationId", "location_getcurrentuserprimarylocation", "id");

    @Mock
    private ToolPrerequisiteRepository repository;

    private PrerequisiteResolverImpl resolver() {
        return new PrerequisiteResolverImpl(repository);
    }

    @Test
    @DisplayName("a required param absent from context is reported with its producing tool")
    void missingParamReported() {
        when(repository.findByToolName("workorder_listwip")).thenReturn(List.of(LOCATION_EDGE));

        List<MissingParam> missing = resolver().missingFor("workorder_listwip", Set.of());

        assertThat(missing).singleElement().satisfies(m -> {
            assertThat(m.param()).isEqualTo("locationId");
            assertThat(m.producingTool()).isEqualTo("location_getcurrentuserprimarylocation");
            assertThat(m.producingField()).isEqualTo("id");
        });
    }

    @Test
    @DisplayName("a required param already known is not reported")
    void knownParamNotReported() {
        when(repository.findByToolName("workorder_listwip")).thenReturn(List.of(LOCATION_EDGE));

        List<MissingParam> missing = resolver().missingFor("workorder_listwip", Set.of("locationId"));

        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("a tool with no declared edges yields no missing params")
    void toolWithoutEdgesYieldsEmpty() {
        when(repository.findByToolName("workorder_countworkorders")).thenReturn(List.of());

        assertThat(resolver().missingFor("workorder_countworkorders", Set.of())).isEmpty();
    }

    @Test
    @DisplayName("only the absent params are reported when context is partial")
    void partialContextReportsOnlyAbsent() {
        ToolPrerequisite secondEdge =
                new ToolPrerequisite("workorder_listwip", "customerId", "location_resolvepartynames", "partyId");
        when(repository.findByToolName("workorder_listwip")).thenReturn(List.of(LOCATION_EDGE, secondEdge));

        List<MissingParam> missing = resolver().missingFor("workorder_listwip", Set.of("locationId"));

        assertThat(missing).extracting(MissingParam::param).containsExactly("customerId");
    }
}
