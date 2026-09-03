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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Selection-verification fixture for Wave 2 (issue #1601, W2.3): a DB-free, no-model regression
 * lock over {@link ToolRegistryService#resolveCandidateTools} — the same mocked-repository harness
 * as {@link ToolRegistryServiceTest}, extended to Wave 2's new/changed operations. Runs in ordinary
 * {@code test} (no {@code -Dmcp.eval.live=true}, no pgvector, no running model), unlike the
 * {@code BaselineCaptureIT} live eval suite documented in {@code src/test/resources/eval/README.md}.
 *
 * <p>Two things are locked here, per the issue:
 *
 * <ul>
 *   <li>Each of Wave 2's newly-promoted facades (E1, E5, E8) — and two representative
 *       OpenAPI-discovery-only operations (E9, E10) — is a member of the assembled candidate set
 *       for a natural-language query touching its domain.
 *   <li>The #1588 admin-fast-path failure mode, specifically re-run against the new accounting
 *       facades: a query carrying the bare word "account"/"accounts" together with an
 *       accounting-analytics intent (vendor spend, A/P aging) must not collapse the candidate set
 *       to {@code AdminFacadeTool} alone — {@code AccountingFacadeTool} must still be offered.
 *       {@code account}/{@code accounts} were deliberately dropped from {@code
 *       ToolRegistryService.ADMIN_QUERY_KEYWORDS} by #1588 for exactly this reason; this fixture
 *       is what keeps that keyword from silently creeping back in.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class Wave2ToolSelectionRegressionTest {

    @Mock
    private ToolMetadataRepository repository;

    @Mock
    private EmbeddingModel embeddingModel;

    private ToolRegistryService service;

    private static final Set<String> ACCOUNTING_ANALYTICS_PERMISSIONS =
            Set.of("AUTHENTICATED", "accounting:analytics:view", "accounting:coa:view");

    private static final Set<String> INVOICE_ANALYTICS_PERMISSIONS = Set.of("AUTHENTICATED", "invoice:analytics:view");

    private static final Set<String> WORKORDER_ANALYTICS_PERMISSIONS =
            Set.of("AUTHENTICATED", "workorder:analytics:view");

    private static final Set<String> ADMIN_AND_ACCOUNTING_PERMISSIONS =
            Set.of("AUTHENTICATED", "security:user:view", "accounting:analytics:view", "accounting:coa:view");

    /** Facade rows are keyed per CLASS (V34/V39 convention) — one row covers every @Tool method. */
    private static final ToolMetadata ACCOUNTING_FACADE_TOOL = new ToolMetadata(
            UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
            "AccountingFacadeTool",
            "Accounting",
            "Accounting summaries and ledger-facing business context: GL account balances, "
                    + "general-ledger activity, income statement / balance sheet / trial balance summaries, "
                    + "per-customer A/R and per-vendor A/P aging reports, and per-vendor spend for a "
                    + "reporting period (top vendors by settled A/P cash, bill count, and average bill "
                    + "amount).",
            "accounting",
            0.8,
            "high",
            320,
            true,
            "accountingFacadeTool");

    private static final ToolMetadata INVOICE_FACADE_TOOL = new ToolMetadata(
            UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
            "InvoiceFacadeTool",
            "Invoice",
            "Invoice lookup, invoice search, a customer's distinct invoices, and per-customer revenue "
                    + "for a reporting period (top customers by revenue, invoice count, average invoice "
                    + "value, and most recent invoice date).",
            "invoice",
            0.9,
            "medium",
            250,
            true,
            "invoiceFacadeTool");

    private static final ToolMetadata WORKORDER_FACADE_TOOL = new ToolMetadata(
            UUID.fromString("00000000-0000-0000-0000-0000000000c1"),
            "WorkorderFacadeTool",
            "Workorder",
            "Workorder lookup, workorder search, workorder status, and per-technician labor and "
                    + "revenue summaries for a reporting period (completed workorder count, billed hours, "
                    + "and labor revenue, per technician).",
            "workorder",
            1.1,
            "medium",
            260,
            true,
            "workorderFacadeTool");

    private static final ToolMetadata ADMIN_FACADE_TOOL = new ToolMetadata(
            UUID.fromString("00000000-0000-0000-0000-0000000000d1"),
            "AdminFacadeTool",
            "Admin",
            "Administrative controls and access governance: user listing, permission lookup, and audit log.",
            "admin",
            1.3,
            "low",
            320,
            true,
            "adminFacadeTool");

    /** E9 (#1597): OpenAPI-discovery-only op, name = {domain}_{operationId} (OpenApiToolMapper). */
    private static final ToolMetadata LIST_VENDOR_BILLS = new ToolMetadata(
            UUID.fromString("00000000-0000-0000-0000-0000000000e1"),
            "accounting_listVendorBills",
            "List Vendor Bills By Due Date",
            "Lists vendor bills whose due date falls in a window, optionally filtered by status.",
            "accounting",
            0.5,
            "low",
            120,
            true,
            "openapi");

    /** E10 (#1598): OpenAPI-discovery-only op. */
    private static final ToolMetadata LIST_PAYMENT_APPLICATIONS = new ToolMetadata(
            UUID.fromString("00000000-0000-0000-0000-0000000000e2"),
            "accounting_listPaymentApplications",
            "List Payment Applications By Applied Date",
            "Lists cash applications of customer payments to invoices whose applied date falls in a window.",
            "accounting",
            0.5,
            "low",
            120,
            true,
            "openapi");

    @BeforeEach
    void setUp() {
        service = new ToolRegistryService(repository, embeddingModel);
    }

    @Test
    @DisplayName("E8: a vendor-spend question offers AccountingFacadeTool as a candidate")
    void vendorSpendQuestion_offersAccountingFacadeTool() {
        ToolSelectionContext context = new ToolSelectionContext(
                "What was our vendor spend last month, top vendors by paid amount?",
                "ROLE_LOCATION_MANAGER",
                "IDLE",
                ACCOUNTING_ANALYTICS_PERMISSIONS);
        float[] vector = new float[] {0.3f, 0.4f};

        when(repository.findEnabledByPermissionsAndWorkflow(ACCOUNTING_ANALYTICS_PERMISSIONS, "IDLE"))
                .thenReturn(List.of(ACCOUNTING_FACADE_TOOL));
        when(embeddingModel.embed(anyString())).thenReturn(vector);
        when(repository.findTopKByEmbeddingForPermissions(
                        any(float[].class), anyInt(), eq(ACCOUNTING_ANALYTICS_PERMISSIONS), eq("IDLE")))
                .thenReturn(List.of(ACCOUNTING_FACADE_TOOL));

        List<ToolMetadata> result = service.resolveCandidateTools(context, 5);

        assertThat(result).extracting(ToolMetadata::name).contains("AccountingFacadeTool");
    }

    @Test
    @DisplayName("E5: a technician-labor question offers WorkorderFacadeTool as a candidate")
    void technicianLaborQuestion_offersWorkorderFacadeTool() {
        ToolSelectionContext context = new ToolSelectionContext(
                "Show me technician labor hours and revenue for last month, ranked by billed hours.",
                "ROLE_SHOP_MANAGER",
                "IDLE",
                WORKORDER_ANALYTICS_PERMISSIONS);
        float[] vector = new float[] {0.2f, 0.5f};

        when(repository.findEnabledByPermissionsAndWorkflow(WORKORDER_ANALYTICS_PERMISSIONS, "IDLE"))
                .thenReturn(List.of(WORKORDER_FACADE_TOOL));
        when(embeddingModel.embed(anyString())).thenReturn(vector);
        when(repository.findTopKByEmbeddingForPermissions(
                        any(float[].class), anyInt(), eq(WORKORDER_ANALYTICS_PERMISSIONS), eq("IDLE")))
                .thenReturn(List.of(WORKORDER_FACADE_TOOL));

        List<ToolMetadata> result = service.resolveCandidateTools(context, 5);

        assertThat(result).extracting(ToolMetadata::name).contains("WorkorderFacadeTool");
    }

    @Test
    @DisplayName("E1: a revenue-by-customer question offers InvoiceFacadeTool as a candidate")
    void revenueByCustomerQuestion_offersInvoiceFacadeTool() {
        ToolSelectionContext context = new ToolSelectionContext(
                "Which customers generated the most revenue last quarter?",
                "ROLE_LOCATION_MANAGER",
                "IDLE",
                INVOICE_ANALYTICS_PERMISSIONS);
        float[] vector = new float[] {0.6f, 0.1f};

        when(repository.findEnabledByPermissionsAndWorkflow(INVOICE_ANALYTICS_PERMISSIONS, "IDLE"))
                .thenReturn(List.of(INVOICE_FACADE_TOOL));
        when(embeddingModel.embed(anyString())).thenReturn(vector);
        when(repository.findTopKByEmbeddingForPermissions(
                        any(float[].class), anyInt(), eq(INVOICE_ANALYTICS_PERMISSIONS), eq("IDLE")))
                .thenReturn(List.of(INVOICE_FACADE_TOOL));

        List<ToolMetadata> result = service.resolveCandidateTools(context, 5);

        assertThat(result).extracting(ToolMetadata::name).contains("InvoiceFacadeTool");
    }

    @Test
    @DisplayName("E4 (#1660): an invoicing-lag-by-month question offers InvoiceFacadeTool as a candidate")
    void invoicingLagQuestion_offersInvoiceFacadeTool() {
        ToolSelectionContext context = new ToolSelectionContext(
                "What was the average time from work order creation to invoice, by month, for the last six "
                        + "months?",
                "ROLE_LOCATION_MANAGER",
                "IDLE",
                INVOICE_ANALYTICS_PERMISSIONS);
        float[] vector = new float[] {0.5f, 0.2f};

        when(repository.findEnabledByPermissionsAndWorkflow(INVOICE_ANALYTICS_PERMISSIONS, "IDLE"))
                .thenReturn(List.of(INVOICE_FACADE_TOOL));
        when(embeddingModel.embed(anyString())).thenReturn(vector);
        when(repository.findTopKByEmbeddingForPermissions(
                        any(float[].class), anyInt(), eq(INVOICE_ANALYTICS_PERMISSIONS), eq("IDLE")))
                .thenReturn(List.of(INVOICE_FACADE_TOOL));

        List<ToolMetadata> result = service.resolveCandidateTools(context, 5);

        assertThat(result).extracting(ToolMetadata::name).contains("InvoiceFacadeTool");
    }

    @Test
    @DisplayName("E9: a vendor-bills-due question offers the discovered listVendorBills operation")
    void vendorBillsDueQuestion_offersDiscoveredListVendorBills() {
        ToolSelectionContext context = new ToolSelectionContext(
                "List vendor bills due this month that are still unpaid.",
                "ROLE_LOCATION_MANAGER",
                "IDLE",
                ACCOUNTING_ANALYTICS_PERMISSIONS);
        float[] vector = new float[] {0.1f, 0.9f};

        when(repository.findEnabledByPermissionsAndWorkflow(ACCOUNTING_ANALYTICS_PERMISSIONS, "IDLE"))
                .thenReturn(List.of(LIST_VENDOR_BILLS));
        when(embeddingModel.embed(anyString())).thenReturn(vector);
        when(repository.findTopKByEmbeddingForPermissions(
                        any(float[].class), anyInt(), eq(ACCOUNTING_ANALYTICS_PERMISSIONS), eq("IDLE")))
                .thenReturn(List.of(LIST_VENDOR_BILLS));

        List<ToolMetadata> result = service.resolveCandidateTools(context, 5);

        assertThat(result).extracting(ToolMetadata::name).contains("accounting_listVendorBills");
    }

    @Test
    @DisplayName("E10: a payment-applications question offers the discovered listPaymentApplications operation")
    void paymentApplicationsQuestion_offersDiscoveredListPaymentApplications() {
        ToolSelectionContext context = new ToolSelectionContext(
                "List payment applications applied in the last two weeks.",
                "ROLE_LOCATION_MANAGER",
                "IDLE",
                ACCOUNTING_ANALYTICS_PERMISSIONS);
        float[] vector = new float[] {0.4f, 0.7f};

        when(repository.findEnabledByPermissionsAndWorkflow(ACCOUNTING_ANALYTICS_PERMISSIONS, "IDLE"))
                .thenReturn(List.of(LIST_PAYMENT_APPLICATIONS));
        when(embeddingModel.embed(anyString())).thenReturn(vector);
        when(repository.findTopKByEmbeddingForPermissions(
                        any(float[].class), anyInt(), eq(ACCOUNTING_ANALYTICS_PERMISSIONS), eq("IDLE")))
                .thenReturn(List.of(LIST_PAYMENT_APPLICATIONS));

        List<ToolMetadata> result = service.resolveCandidateTools(context, 5);

        assertThat(result).extracting(ToolMetadata::name).contains("accounting_listPaymentApplications");
    }

    @Test
    @DisplayName("#1588 regression, Wave 2 accounting facades: bare 'account'/'accounts' plus vendor-spend "
            + "intent does not collapse the candidate set to AdminFacadeTool alone")
    void accountsWordWithVendorSpendIntent_doesNotUseAdminFastPath() {
        // Deliberately uses the bare words "account"/"accounts" — the exact term #1588 removed from
        // ToolRegistryService.ADMIN_QUERY_KEYWORDS — together with a vendor-spend/A-P-aging intent.
        // The actor holds BOTH security:user:view (AdminFacadeTool is genuinely reachable) AND the
        // accounting permissions, so this proves the fast path itself does not fire here, not merely
        // that the actor lacks admin access.
        ToolSelectionContext context = new ToolSelectionContext(
                "Which vendor accounts make up most of our accounts payable, and what's our vendor "
                        + "spend by account this quarter?",
                "ROLE_SYSTEM_ADMINISTRATOR",
                "IDLE",
                ADMIN_AND_ACCOUNTING_PERMISSIONS);
        float[] vector = new float[] {0.5f, 0.5f};

        when(repository.findEnabledByPermissionsAndWorkflow(ADMIN_AND_ACCOUNTING_PERMISSIONS, "IDLE"))
                .thenReturn(List.of(ADMIN_FACADE_TOOL, ACCOUNTING_FACADE_TOOL));
        when(embeddingModel.embed(anyString())).thenReturn(vector);
        // Semantic ranking (not the fast path) is what must be reached, and it is what correctly
        // favors the accounting facade for this query.
        when(repository.findTopKByEmbeddingForPermissions(
                        any(float[].class), anyInt(), eq(ADMIN_AND_ACCOUNTING_PERMISSIONS), eq("IDLE")))
                .thenReturn(List.of(ACCOUNTING_FACADE_TOOL));

        List<ToolMetadata> result = service.resolveCandidateTools(context, 5);

        assertThat(result).extracting(ToolMetadata::name).contains("AccountingFacadeTool");
        assertThat(result).extracting(ToolMetadata::name).doesNotContain("AdminFacadeTool");
        assertThat(result).isNotEqualTo(List.of(ADMIN_FACADE_TOOL));
    }
}
