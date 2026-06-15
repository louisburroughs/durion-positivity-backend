package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolSelectionContext;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import java.util.List;
import java.util.Set;
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

    private static final Set<String> CASHIER_PERMISSIONS = Set.of("AUTHENTICATED", "customer:account:view");

    private static final Set<String> ADMIN_PERMISSIONS =
            Set.of("AUTHENTICATED", "security:user:view", "security:audit:view");

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

    private static final ToolMetadata ADMIN_TOOL = new ToolMetadata(
            UUID.fromString("00000000-0000-0000-0000-000000000010"),
            "AdminFacadeTool",
            "Admin",
            "Administrative controls and access governance",
            "admin",
            1.3,
            "low",
            320,
            true,
            "adminFacadeTool");

    @BeforeEach
    void setUp() {
        service = new ToolRegistryService(repository, embeddingModel);
    }

    @Test
    @DisplayName("resolveCandidateTools returns scored and limited list when gated tools exist")
    void resolveCandidateTools_withGatedTools_returnsScoredList() {
        ToolSelectionContext context =
                new ToolSelectionContext("look up customer", "ROLE_CASHIER", "IDLE", CASHIER_PERMISSIONS);
        float[] vector = new float[] {0.1f, 0.2f, 0.3f};

        when(repository.findEnabledByPermissionsAndWorkflow(CASHIER_PERMISSIONS, "IDLE"))
                .thenReturn(List.of(SAMPLE_TOOL));
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(vector)));
        when(repository.findTopKByEmbeddingForPermissions(eq(vector), anyInt(), eq(CASHIER_PERMISSIONS), eq("IDLE")))
                .thenReturn(List.of(SAMPLE_TOOL));

        List<ToolMetadata> result = service.resolveCandidateTools(context, 5);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().name()).isEqualTo("customerFacadeTool");
    }

    @Test
    @DisplayName("resolveCandidateTools returns empty list when no gated tools exist for permissions/workflow")
    void resolveCandidateTools_withNoGatedTools_returnsEmpty() {
        ToolSelectionContext context =
                new ToolSelectionContext("look up customer", "ROLE_CASHIER", "IDLE", CASHIER_PERMISSIONS);

        when(repository.findEnabledByPermissionsAndWorkflow(CASHIER_PERMISSIONS, "IDLE"))
                .thenReturn(List.of());

        List<ToolMetadata> result = service.resolveCandidateTools(context, 5);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("resolveCandidateTools returns empty list when topK is zero")
    void resolveCandidateTools_withZeroTopK_returnsEmpty() {
        ToolSelectionContext context =
                new ToolSelectionContext("look up customer", "ROLE_CASHIER", "IDLE", CASHIER_PERMISSIONS);

        List<ToolMetadata> result = service.resolveCandidateTools(context, 0);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("resolveCandidateTools falls back to priority-ordered gated set when ANN returns empty")
    void resolveCandidateTools_fallsBackToGatedSet_whenAnnReturnsEmpty() {
        // The gated ANN returns only authorized tools, so an unauthorized tool can
        // never displace an authorized one.
        ToolSelectionContext context =
                new ToolSelectionContext("look up customer", "ROLE_CASHIER", "IDLE", CASHIER_PERMISSIONS);
        float[] vector = new float[] {0.1f, 0.2f};

        when(repository.findEnabledByPermissionsAndWorkflow(CASHIER_PERMISSIONS, "IDLE"))
                .thenReturn(List.of(SAMPLE_TOOL));
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(vector)));
        // Gated ANN returns empty — simulates no embeddings for authorized tools
        when(repository.findTopKByEmbeddingForPermissions(
                        any(float[].class), anyInt(), eq(CASHIER_PERMISSIONS), eq("IDLE")))
                .thenReturn(List.of());

        // Should fall back to priority-ordered gated tools, not empty
        List<ToolMetadata> result = service.resolveCandidateTools(context, 5);

        assertThat(result).containsExactly(SAMPLE_TOOL);
    }

    @Test
    @DisplayName("resolveCandidateTools returns authorized tool that would have been excluded by global ANN")
    void resolveCandidateTools_authorizedToolNotInGlobalTopK_isReturnedByGatedAnn() {
        // Correctness proof: an authorized tool ranks beyond the global top-K but is
        // correctly returned because the gated ANN searches only authorized tools.
        ToolSelectionContext context =
                new ToolSelectionContext("look up customer", "ROLE_CASHIER", "IDLE", CASHIER_PERMISSIONS);
        float[] vector = new float[] {0.1f, 0.2f};

        when(repository.findEnabledByPermissionsAndWorkflow(CASHIER_PERMISSIONS, "IDLE"))
                .thenReturn(List.of(SAMPLE_TOOL));
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(vector)));
        // Gated ANN finds the authorized tool directly — no Java-filter step
        when(repository.findTopKByEmbeddingForPermissions(
                        any(float[].class), anyInt(), eq(CASHIER_PERMISSIONS), eq("IDLE")))
                .thenReturn(List.of(SAMPLE_TOOL));

        List<ToolMetadata> result = service.resolveCandidateTools(context, 5);

        assertThat(result).containsExactly(SAMPLE_TOOL);
    }

    @Test
    @DisplayName("resolveCandidateTools uses admin fast-path for user and access questions")
    void resolveCandidateTools_adminQuery_usesAdminFastPath() {
        ToolSelectionContext context = new ToolSelectionContext(
                "How many users do I have in the system?", "ROLE_ADMIN", "IDLE", ADMIN_PERMISSIONS);

        when(repository.findEnabledByPermissionsAndWorkflow(ADMIN_PERMISSIONS, "IDLE"))
                .thenReturn(List.of(ADMIN_TOOL, SAMPLE_TOOL));

        List<ToolMetadata> result = service.resolveCandidateTools(context, 2);

        assertThat(result).containsExactly(ADMIN_TOOL);
    }

    @Test
    @DisplayName("resolveCandidateTools uses admin fast-path for audit and account-governance questions")
    void resolveCandidateTools_adminAuditQuery_usesAdminFastPath() {
        ToolSelectionContext context = new ToolSelectionContext(
                "Search the audit log for failed admin logins", "ROLE_ADMIN", "IDLE", ADMIN_PERMISSIONS);

        when(repository.findEnabledByPermissionsAndWorkflow(ADMIN_PERMISSIONS, "IDLE"))
                .thenReturn(List.of(ADMIN_TOOL, SAMPLE_TOOL));

        List<ToolMetadata> result = service.resolveCandidateTools(context, 2);

        assertThat(result).containsExactly(ADMIN_TOOL);
    }

    @Test
    @DisplayName("resolveCandidateTools does not use admin fast-path when caller lacks AdminFacadeTool permission")
    void resolveCandidateTools_withoutAdminPermission_doesNotUseAdminFastPath() {
        ToolSelectionContext context = new ToolSelectionContext(
                "How many users do I have in the system?", "ROLE_MANAGER", "IDLE", CASHIER_PERMISSIONS);
        float[] vector = new float[] {0.4f, 0.5f};

        when(repository.findEnabledByPermissionsAndWorkflow(CASHIER_PERMISSIONS, "IDLE"))
                .thenReturn(List.of(SAMPLE_TOOL));
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(vector)));
        when(repository.findTopKByEmbeddingForPermissions(
                        any(float[].class), anyInt(), eq(CASHIER_PERMISSIONS), eq("IDLE")))
                .thenReturn(List.of(SAMPLE_TOOL));

        List<ToolMetadata> result = service.resolveCandidateTools(context, 2);

        assertThat(result).containsExactly(SAMPLE_TOOL);
    }

    @Test
    @DisplayName("resolveCandidateTools gates on permissionCodes rather than role — same role, "
            + "different permissions yield different candidates")
    void resolveCandidateTools_sameRoleDifferentPermissions_yieldsDifferentCandidates() {
        String role = "ROLE_CASHIER";
        Set<String> narrowPermissions = Set.of("AUTHENTICATED");
        Set<String> broadPermissions = CASHIER_PERMISSIONS;
        float[] vector = new float[] {0.1f, 0.2f};

        ToolSelectionContext narrowContext =
                new ToolSelectionContext("look up customer", role, "IDLE", narrowPermissions);
        ToolSelectionContext broadContext =
                new ToolSelectionContext("look up customer", role, "IDLE", broadPermissions);

        when(repository.findEnabledByPermissionsAndWorkflow(narrowPermissions, "IDLE"))
                .thenReturn(List.of());
        when(repository.findEnabledByPermissionsAndWorkflow(broadPermissions, "IDLE"))
                .thenReturn(List.of(SAMPLE_TOOL));
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(vector)));
        when(repository.findTopKByEmbeddingForPermissions(
                        any(float[].class), anyInt(), eq(broadPermissions), eq("IDLE")))
                .thenReturn(List.of(SAMPLE_TOOL));

        List<ToolMetadata> narrowResult = service.resolveCandidateTools(narrowContext, 5);
        List<ToolMetadata> broadResult = service.resolveCandidateTools(broadContext, 5);

        assertThat(narrowResult).isEmpty();
        assertThat(broadResult).containsExactly(SAMPLE_TOOL);
    }
}
