package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolSelectionContext;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ToolRegistryService}: verifies candidate tool
 * resolution and scoring.
 */
@ExtendWith(MockitoExtension.class)
class ToolRegistryServiceTest {

    @Mock
    private ToolMetadataRepository repository;

    @Mock
    private EmbeddingModel embeddingModel;

    private ToolRegistryService service;

    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final ToolMetadata SAMPLE_TOOL = new ToolMetadata(
            TOOL_ID,
            "customerFacadeTool",
            "Customer Lookup",
            "Look up customer records",
            "customer",
            0.8,
            "low",
            50,
            true,
            "customerFacadeTool");

    @BeforeEach
    void setUp() {
        service = new ToolRegistryService(repository, embeddingModel);
    }

    @Test
    @DisplayName("resolveCandidateTools returns scored and limited list when gated tools exist")
    void resolveCandidateTools_withGatedTools_returnsScoredList() {
        ToolSelectionContext context = new ToolSelectionContext("look up customer", "ROLE_CASHIER", "IDLE");
        float[] vector = new float[] {0.1f, 0.2f, 0.3f};

        when(repository.findEnabledByRoleAndWorkflow("ROLE_CASHIER", "IDLE")).thenReturn(List.of(SAMPLE_TOOL));
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(vector)));
        when(repository.findTopKByEmbedding(vector, 10)).thenReturn(List.of(SAMPLE_TOOL));

        List<ToolMetadata> result = service.resolveCandidateTools(context, 5);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().name()).isEqualTo("customerFacadeTool");
    }

    @Test
    @DisplayName("resolveCandidateTools returns empty list when no gated tools exist for role/workflow")
    void resolveCandidateTools_withNoGatedTools_returnsEmpty() {
        ToolSelectionContext context = new ToolSelectionContext("look up customer", "ROLE_CASHIER", "IDLE");

        when(repository.findEnabledByRoleAndWorkflow("ROLE_CASHIER", "IDLE")).thenReturn(List.of());

        List<ToolMetadata> result = service.resolveCandidateTools(context, 5);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("resolveCandidateTools returns empty list when topK is zero")
    void resolveCandidateTools_withZeroTopK_returnsEmpty() {
        ToolSelectionContext context = new ToolSelectionContext("look up customer", "ROLE_CASHIER", "IDLE");

        List<ToolMetadata> result = service.resolveCandidateTools(context, 0);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("resolveCandidateTools filters semantic candidates not in gated role-workflow set")
    void resolveCandidateTools_filtersToolsNotInGatedSet() {
        ToolSelectionContext context = new ToolSelectionContext("look up customer", "ROLE_CASHIER", "IDLE");
        float[] vector = new float[] {0.1f, 0.2f};

        UUID otherId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        ToolMetadata otherTool = new ToolMetadata(
                otherId, "otherTool", "Other Tool", "Other", "other", 0.5, "low", 100, true, "otherTool");

        when(repository.findEnabledByRoleAndWorkflow("ROLE_CASHIER", "IDLE")).thenReturn(List.of(SAMPLE_TOOL));
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(vector)));
        when(repository.findTopKByEmbedding(vector, 10)).thenReturn(List.of(otherTool));

        List<ToolMetadata> result = service.resolveCandidateTools(context, 5);

        assertThat(result).isEmpty();
    }
}
