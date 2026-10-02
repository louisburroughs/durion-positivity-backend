package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolPriorityOverlay;
import com.positivity.mcp.internal.domain.ToolSelectionContext;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import com.positivity.mcp.internal.repository.ToolPriorityRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Unit tests for {@link ToolRegistryService}: verifies candidate tool
 * resolution and scoring.
 */
@ExtendWith(MockitoExtension.class)
class ToolRegistryServiceTest {

    @Mock
    private ToolMetadataRepository repository;

    /** Mockito answers an empty map: no overlay, so every test below runs on the global priorities. */
    @Mock
    private ToolPriorityRepository priorityRepository;

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
        service =
                new ToolRegistryService(repository, embeddingModel, new TenantToolPriorityResolver(priorityRepository));
    }

    @Test
    @DisplayName("resolveCandidateTools returns scored and limited list when gated tools exist")
    void resolveCandidateTools_withGatedTools_returnsScoredList() {
        ToolSelectionContext context =
                new ToolSelectionContext("look up customer", "ROLE_CASHIER", "IDLE", CASHIER_PERMISSIONS);
        float[] vector = new float[] {0.1f, 0.2f, 0.3f};

        when(repository.findEnabledByPermissionsAndWorkflow(CASHIER_PERMISSIONS, "IDLE"))
                .thenReturn(List.of(SAMPLE_TOOL));
        when(embeddingModel.embed(anyString())).thenReturn(vector);
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
        when(embeddingModel.embed(anyString())).thenReturn(vector);
        // Gated ANN returns empty — simulates no embeddings for authorized tools
        when(repository.findTopKByEmbeddingForPermissions(
                        any(float[].class), anyInt(), eq(CASHIER_PERMISSIONS), eq("IDLE")))
                .thenReturn(List.of());

        // Should fall back to priority-ordered gated tools, not empty
        List<ToolMetadata> result = service.resolveCandidateTools(context, 5);

        assertThat(result).containsExactly(SAMPLE_TOOL);
    }

    @Test
    @DisplayName("the bound tenant's priority overlay reorders the priority fallback (ADR-0062 WS6)")
    void resolveCandidateTools_appliesTenantOverlayToPriorityFallback() {
        ToolSelectionContext context =
                new ToolSelectionContext("look up customer", "ROLE_CASHIER", "IDLE", CASHIER_PERMISSIONS);
        ToolMetadata inventory = new ToolMetadata(
                UUID.fromString("00000000-0000-0000-0000-000000000020"),
                "InventoryFacadeTool",
                "Inventory",
                "Inventory availability",
                "inventory",
                1.0,
                "low",
                220,
                true,
                "inventoryFacadeTool");
        // Globally, inventory (1.0) outranks the customer tool (0.8); this tenant's overlay says otherwise.
        when(repository.findEnabledByPermissionsAndWorkflow(CASHIER_PERMISSIONS, "IDLE"))
                .thenReturn(List.of(inventory, SAMPLE_TOOL));
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        when(repository.findTopKByEmbeddingForPermissions(
                        any(float[].class), anyInt(), eq(CASHIER_PERMISSIONS), eq("IDLE")))
                .thenReturn(List.of());
        when(priorityRepository.findOverlayForCurrentTenant())
                .thenReturn(Map.of(
                        TOOL_ID,
                        new ToolPriorityOverlay(TOOL_ID, 0.95, 50),
                        inventory.id(),
                        new ToolPriorityOverlay(inventory.id(), 0.3, 900)));

        List<ToolMetadata> result = service.resolveCandidateTools(context, 5);

        assertThat(result).extracting(ToolMetadata::name).containsExactly("customerFacadeTool", "InventoryFacadeTool");
        assertThat(result.getFirst().priority()).isEqualTo(0.95);
        assertThat(result.get(1).avgLatencyMs()).isEqualTo(900);
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
        when(embeddingModel.embed(anyString())).thenReturn(vector);
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
    @DisplayName(
            "ADR-0069 §6: resolveCandidateSelection returns the same ranked cut plus the whole gated set and the fast-path flag")
    void resolveCandidateSelection_exposesTheGatedSetAndTheFastPath() {
        float[] vector = new float[] {0.1f, 0.2f, 0.3f};
        when(embeddingModel.embed(anyString())).thenReturn(vector);
        when(repository.findEnabledByPermissionsAndWorkflow(ADMIN_PERMISSIONS, "IDLE"))
                .thenReturn(List.of(ADMIN_TOOL, SAMPLE_TOOL));
        when(repository.findTopKByEmbeddingForPermissions(eq(vector), anyInt(), eq(ADMIN_PERMISSIONS), eq("IDLE")))
                .thenReturn(List.of(SAMPLE_TOOL));

        ToolRegistryService.CandidateSelection ranked = service.resolveCandidateSelection(
                new ToolSelectionContext("look up customer", "ROLE_ADMIN", "IDLE", ADMIN_PERMISSIONS), 2);
        assertThat(ranked.candidates()).containsExactly(SAMPLE_TOOL);
        assertThat(ranked.gatedToolNames()).containsExactlyInAnyOrder(ADMIN_TOOL.name(), SAMPLE_TOOL.name());
        assertThat(ranked.adminFastPath()).isFalse();

        ToolRegistryService.CandidateSelection fastPath = service.resolveCandidateSelection(
                new ToolSelectionContext(
                        "How many users do I have in the system?", "ROLE_ADMIN", "IDLE", ADMIN_PERMISSIONS),
                2);
        assertThat(fastPath.candidates()).containsExactly(ADMIN_TOOL);
        assertThat(fastPath.gatedToolNames()).containsExactlyInAnyOrder(ADMIN_TOOL.name(), SAMPLE_TOOL.name());
        assertThat(fastPath.adminFastPath()).isTrue();

        when(repository.findEnabledByPermissionsAndWorkflow(CASHIER_PERMISSIONS, "IDLE"))
                .thenReturn(List.of());
        assertThat(service.resolveCandidateSelection(
                        new ToolSelectionContext("look up customer", "ROLE_CASHIER", "IDLE", CASHIER_PERMISSIONS), 2))
                .isSameAs(ToolRegistryService.CandidateSelection.EMPTY);
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
    @DisplayName("admin fast-path does not hijack an accounts-receivable question (bare 'accounts' is not a keyword)")
    void resolveCandidateTools_accountsReceivableQuestion_doesNotUseAdminFastPath() {
        ToolSelectionContext context = new ToolSelectionContext(
                "Which customers make up most of our accounts receivable balance, "
                        + "and how much of each is past due?",
                "ROLE_SYSTEM_ADMINISTRATOR",
                "IDLE",
                ADMIN_PERMISSIONS);
        float[] vector = new float[] {0.7f, 0.1f};

        when(repository.findEnabledByPermissionsAndWorkflow(ADMIN_PERMISSIONS, "IDLE"))
                .thenReturn(List.of(ADMIN_TOOL, SAMPLE_TOOL));
        when(embeddingModel.embed(anyString())).thenReturn(vector);
        when(repository.findTopKByEmbeddingForPermissions(
                        any(float[].class), anyInt(), eq(ADMIN_PERMISSIONS), eq("IDLE")))
                .thenReturn(List.of(SAMPLE_TOOL));

        List<ToolMetadata> result = service.resolveCandidateTools(context, 2);

        assertThat(result).containsExactly(SAMPLE_TOOL);
    }

    @Test
    @DisplayName("domain vocabulary vetoes the fast-path even when an admin keyword is present")
    void resolveCandidateTools_adminKeywordWithDomainVocabulary_vetoesFastPath() {
        ToolSelectionContext context = new ToolSelectionContext(
                "Who has access to the receivables ledger?", "ROLE_SYSTEM_ADMINISTRATOR", "IDLE", ADMIN_PERMISSIONS);
        float[] vector = new float[] {0.2f, 0.9f};

        when(repository.findEnabledByPermissionsAndWorkflow(ADMIN_PERMISSIONS, "IDLE"))
                .thenReturn(List.of(ADMIN_TOOL, SAMPLE_TOOL));
        when(embeddingModel.embed(anyString())).thenReturn(vector);
        when(repository.findTopKByEmbeddingForPermissions(
                        any(float[].class), anyInt(), eq(ADMIN_PERMISSIONS), eq("IDLE")))
                .thenReturn(List.of(SAMPLE_TOOL));

        List<ToolMetadata> result = service.resolveCandidateTools(context, 2);

        assertThat(result).containsExactly(SAMPLE_TOOL);
    }

    @Test
    @DisplayName("admin fast-path still fires for a genuine user-account question")
    void resolveCandidateTools_userAccountQuestion_usesAdminFastPath() {
        ToolSelectionContext context = new ToolSelectionContext(
                "Is this user account still active?", "ROLE_SYSTEM_ADMINISTRATOR", "IDLE", ADMIN_PERMISSIONS);

        when(repository.findEnabledByPermissionsAndWorkflow(ADMIN_PERMISSIONS, "IDLE"))
                .thenReturn(List.of(ADMIN_TOOL, SAMPLE_TOOL));

        List<ToolMetadata> result = service.resolveCandidateTools(context, 2);

        assertThat(result).containsExactly(ADMIN_TOOL);
    }

    @Test
    @DisplayName("#2371: a customer's account state vetoes the fast path although 'account state' is an admin phrase")
    void resolveCandidateTools_customerAccountState_vetoesFastPath() {
        ToolSelectionContext context = new ToolSelectionContext(
                "Show the customer's account state", "ROLE_SYSTEM_ADMINISTRATOR", "IDLE", ADMIN_PERMISSIONS);
        float[] vector = new float[] {0.3f, 0.6f};

        when(repository.findEnabledByPermissionsAndWorkflow(ADMIN_PERMISSIONS, "IDLE"))
                .thenReturn(List.of(ADMIN_TOOL, SAMPLE_TOOL));
        when(embeddingModel.embed(anyString())).thenReturn(vector);
        when(repository.findTopKByEmbeddingForPermissions(
                        any(float[].class), anyInt(), eq(ADMIN_PERMISSIONS), eq("IDLE")))
                .thenReturn(List.of(SAMPLE_TOOL));

        ToolRegistryService.CandidateSelection selection = service.resolveCandidateSelection(context, 2);

        assertThat(selection.adminFastPath()).isFalse();
        assertThat(selection.candidates()).containsExactly(SAMPLE_TOOL);
    }

    @Test
    @DisplayName("#2371: a platform user's account state still takes the fast path")
    void resolveCandidateTools_platformUserAccountState_usesAdminFastPath() {
        ToolSelectionContext context = new ToolSelectionContext(
                "Show the account state for user jdoe", "ROLE_SYSTEM_ADMINISTRATOR", "IDLE", ADMIN_PERMISSIONS);

        when(repository.findEnabledByPermissionsAndWorkflow(ADMIN_PERMISSIONS, "IDLE"))
                .thenReturn(List.of(ADMIN_TOOL, SAMPLE_TOOL));

        ToolRegistryService.CandidateSelection selection = service.resolveCandidateSelection(context, 2);

        assertThat(selection.adminFastPath()).isTrue();
        assertThat(selection.candidates()).containsExactly(ADMIN_TOOL);
    }

    /**
     * #2371: every term the issue added vetoes an otherwise-admin question ("who has access" is an admin
     * phrase, "access" a keyword). Terms that contain an older or shorter term ({@code ledger account},
     * {@code accounts receivable}, {@code customers}, {@code cliente}, {@code bank account}) veto either
     * way; {@link #adminAccountRule_namesTheNewVetoTerm} pins the ones that act on their own.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "customer",
                "customers",
                "party",
                "parties",
                "supplier",
                "vendor",
                "bank",
                "bank account",
                "ledger account",
                "account balance",
                "accounts receivable",
                "accounts payable",
                "client",
                "cliente",
                "fournisseur",
                "proveedor",
                "banque",
                "bancaire",
                "banco",
                "bancaria",
                "bancario",
                "recevable",
                "comptes à recevoir",
                "comptes à payer",
                "grand livre",
                "grand-livre",
                "grands livres",
                "grands-livres",
                "por cobrar",
                "por pagar",
                "libro mayor",
                "libros mayores"
            })
    @DisplayName("#2371: each added veto term blocks the fast path when an admin phrase is present")
    void isAdminAccountQuestion_addedVetoTerm_blocksFastPath(String term) {
        String message = "who has access to the " + term;

        assertThat(ToolRegistryService.isAdminAccountQuestion(message)).isFalse();
        assertThat(ToolRegistryService.adminAccountRule(message))
                .hasValueSatisfying(rule -> assertThat(rule).startsWith("veto:"));
    }

    /**
     * #2371: en, fr and es questions that fired the fast path before the change, each vetoed by exactly
     * the new term the trace names (the first matched veto term in sorted order).
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '"',
            value = {
                "show the customer's account state|customer",
                "what is the account state of the party record|party",
                "which parties have user accounts|parties",
                "show the supplier's account state|supplier",
                "who has access to the vendor portal|vendor",
                "who has access to the bank account|bank",
                "which users can see the account balance|account balance",
                "who has access to accounts receivable|accounts receivable",
                "who has access to accounts payable|accounts payable",
                "Quelle permission faut-il pour voir le compte du client?|client",
                "Le fournisseur a-t-il la permission d'accéder au portail?|fournisseur",
                "Quelle permission faut-il pour le compte bancaire?|bancaire",
                "Quelle permission faut-il pour le compte en banque?|banque",
                "¿El cliente tiene access a su cuenta?|client",
                "¿El proveedor tiene access al portal?|proveedor",
                "¿Quién tiene access a la cuenta bancaria?|bancaria",
                "¿Quién tiene access al banco?|banco",
                "¿Qué empleado bancario tiene access?|bancario",
                "Quelle permission faut-il pour voir le grand livre?|grand livre",
                "Quelle permission faut-il pour le grand-livre des fournitures?|grand-livre",
                "Quelle permission faut-il pour fermer les grands livres?|grands livres",
                "Quelle permission faut-il pour voir les comptes recevables?|recevable",
                "Quelle permission faut-il pour voir les comptes à recevoir?|comptes à recevoir",
                "Quelle permission faut-il pour voir les comptes à payer?|comptes à payer",
                "Quelle permission faut-il pour voir les comptes clients?|client",
                "Quelle permission faut-il pour voir les dettes fournisseurs?|fournisseur",
                "¿Quién tiene access a las cuentas por cobrar?|por cobrar",
                "¿Quién tiene access a la cuenta por pagar?|por pagar",
                "¿Quién tiene access al libro mayor?|libro mayor",
                "¿Quién tiene access a los libros mayores?|libros mayores"
            })
    @DisplayName("#2371: the trace names the added veto term that blocked an otherwise-admin question")
    void adminAccountRule_namesTheNewVetoTerm(String message, String vetoTerm) {
        assertThat(ToolRegistryService.isAdminAccountQuestion(message)).isFalse();
        assertThat(ToolRegistryService.adminAccountRule(message)).contains("veto:" + vetoTerm);
    }

    /**
     * #2371: veto terms match as substrings, a deliberate choice (see {@code FAST_PATH_VETO_TERMS}): a
     * spurious veto only sends the question to semantic ranking. These pin the accepted substring hits.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '"',
            value = {
                "which third-party users have access|party",
                "who has access to online banking|bank",
                "which API clients have access|client",
                "¿Qué proveedores tienen access al portal?|proveedor"
            })
    @DisplayName("#2371: a veto term inside a longer word still vetoes (accepted substring hit)")
    void adminAccountRule_vetoTermInsideLongerWord_stillVetoes(String message, String vetoTerm) {
        assertThat(ToolRegistryService.isAdminAccountQuestion(message)).isFalse();
        assertThat(ToolRegistryService.adminAccountRule(message)).contains("veto:" + vetoTerm);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "list all users",
                "which roles can approve a purchase",
                "reset the permission for user jdoe",
                "show the account state for user jdoe",
                "Is this user account still active?",
                "who has access to the admin console",
                "deactivate account for the technician who left",
                "how many registered users do we have",
                "show the audit log for logins"
            })
    @DisplayName("#2371: a pure user, role, permission or audit question still takes the fast path")
    void isAdminAccountQuestion_pureAdminQuestion_stillFires(String message) {
        assertThat(ToolRegistryService.isAdminAccountQuestion(message)).isTrue();
        assertThat(ToolRegistryService.adminAccountRule(message))
                .hasValueSatisfying(rule -> assertThat(rule).startsWith("match:"));
    }

    /**
     * #2371: no veto term may occur inside an admin keyword or phrase, or it would veto every question that
     * uses it. Each one alone must still fire. Mirrors {@code ADMIN_QUERY_KEYWORDS} and {@code
     * ADMIN_QUERY_PHRASES}; extend it when either list grows.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "user",
                "users",
                "role",
                "roles",
                "permission",
                "permissions",
                "access",
                "audit",
                "audits",
                "registered",
                "registration",
                "login",
                "logins",
                "who has access",
                "who can access",
                "audit log",
                "access review",
                "user count",
                "registered users",
                "account state",
                "user account",
                "user accounts",
                "account access",
                "disable account",
                "deactivate account"
            })
    @DisplayName("#2371: no veto term hides inside an admin keyword or phrase")
    void isAdminAccountQuestion_adminVocabularyAlone_fires(String adminTerm) {
        assertThat(ToolRegistryService.isAdminAccountQuestion(adminTerm)).isTrue();
    }

    @Test
    @DisplayName("resolveCandidateTools does not use admin fast-path when caller lacks AdminFacadeTool permission")
    void resolveCandidateTools_withoutAdminPermission_doesNotUseAdminFastPath() {
        ToolSelectionContext context = new ToolSelectionContext(
                "How many users do I have in the system?", "ROLE_MANAGER", "IDLE", CASHIER_PERMISSIONS);
        float[] vector = new float[] {0.4f, 0.5f};

        when(repository.findEnabledByPermissionsAndWorkflow(CASHIER_PERMISSIONS, "IDLE"))
                .thenReturn(List.of(SAMPLE_TOOL));
        when(embeddingModel.embed(anyString())).thenReturn(vector);
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
        when(embeddingModel.embed(anyString())).thenReturn(vector);
        when(repository.findTopKByEmbeddingForPermissions(
                        any(float[].class), anyInt(), eq(broadPermissions), eq("IDLE")))
                .thenReturn(List.of(SAMPLE_TOOL));

        List<ToolMetadata> narrowResult = service.resolveCandidateTools(narrowContext, 5);
        List<ToolMetadata> broadResult = service.resolveCandidateTools(broadContext, 5);

        assertThat(narrowResult).isEmpty();
        assertThat(broadResult).containsExactly(SAMPLE_TOOL);
    }
}
