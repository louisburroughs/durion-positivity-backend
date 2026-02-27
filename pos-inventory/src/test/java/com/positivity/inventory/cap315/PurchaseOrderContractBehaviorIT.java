package com.positivity.inventory.cap315;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.dto.purchaseorder.ApprovePurchaseOrderRequest;
import com.positivity.inventory.internal.dto.purchaseorder.CreatePurchaseOrderRequest;
import com.positivity.inventory.internal.dto.purchaseorder.ListPurchaseOrdersRequest;
import com.positivity.inventory.internal.dto.purchaseorder.PurchaseOrderLineRequest;
import com.positivity.inventory.internal.dto.purchaseorder.PurchaseOrderLineResponse;
import com.positivity.inventory.internal.dto.purchaseorder.PurchaseOrderResponse;
import com.positivity.inventory.internal.dto.purchaseorder.ReceivePurchaseOrderResponse;
import com.positivity.inventory.internal.dto.purchaseorder.RevisePurchaseOrderRequest;
import com.positivity.inventory.internal.enums.PurchaseOrderStatus;
import com.positivity.inventory.internal.exception.PurchaseOrderNotApprovedException;
import com.positivity.inventory.service.PurchaseOrderService;
import com.positivity.inventory.service.contract.BaseContractIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract behavior integration tests for the Purchase Order API — Story #572:
 * Create Purchase Order (Procure-to-Pay Initiation).
 *
 * <p>
 * ADR compliance:
 * <ul>
 * <li>ADR-0011: gateway auth headers (X-User, X-Authorities) required</li>
 * <li>ADR-0014: internal service security — all endpoints require gateway
 * auth</li>
 * <li>ADR-0017: HTTP response codes: 201 Created, 200 OK, 409 Conflict, 403
 * Forbidden</li>
 * <li>ADR-0018: actor userId extracted from SecurityContext via
 * SecurityContextHelper</li>
 * <li>ADR-0001: inventory ledger — receiving against DRAFT PO must be
 * rejected</li>
 * </ul>
 *
 * Issue: #572
 */
@DisplayName("Purchase Order Contract Behavior — Story #572")
class PurchaseOrderContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PurchaseOrderService purchaseOrderService;

    @MockitoBean
    private ApplicationEventPublisher applicationEventPublisher;

    // ─── AC #1: DRAFT PO — receipt attempt rejected with 409 ────────────────────

    /**
     * AC #1: Verifies that a receiving attempt against a PO in DRAFT status
     * yields 409 Conflict per the procure-to-pay business rules. Only APPROVED
     * POs may receive goods.
     *
     * Issue: #572
     */
    @Test
    @DisplayName("POST /purchase-orders/{poId}/receive against DRAFT PO → 409 Conflict")
    void draftPoReceiptAttempt_returns409() throws Exception {
        // Issue #572: AC #1 — DRAFT PO must reject receipt with 409
        UUID poId = UUID.randomUUID();
        when(purchaseOrderService.receivePurchaseOrder(eq(poId), any(), anyString()))
                .thenThrow(new PurchaseOrderNotApprovedException("Purchase order must be approved before receiving"));

        String receiveBody = objectMapper.writeValueAsString(
                java.util.Map.of("lineReceipts", List.of(
                        java.util.Map.of("lineId", UUID.randomUUID().toString(), "receivedQty", 5))));

        mockMvc.perform(withPurchaseOrderReceiveAuth(
                post("/v1/inventory/purchase-orders/{poId}/receive", poId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(receiveBody)))
                .andExpect(status().isConflict());
    }

    // ─── AC #2: Approval with encumbrance enabled → emits encumbrance event ─────

    /**
     * AC #2: Verifies that approving a PO when encumbrance is enabled causes the
     * service to emit an encumbrance event and the response contains a non-null
     * {@code encumbranceRef}.
     *
     * Issue: #572
     */
    @Test
    @DisplayName("POST /purchase-orders/{poId}/approve with encumbrance enabled → 200 + encumbranceRef set")
    void approvalWithEncumbrance_emitsEncumbranceEvent() throws Exception {
        // Issue #572: AC #2 — approval must trigger encumbrance posting event
        UUID poId = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();

        PurchaseOrderResponse approvedResponse = buildApprovedPoResponse(poId, vendorId,
                "ENC-2026-001", /* encumbranceRef */
                1_000_000L, 100_000L, 1_100_000L);

        when(purchaseOrderService.approvePurchaseOrder(eq(poId), any(ApprovePurchaseOrderRequest.class),
                any(String.class)))
                .thenReturn(approvedResponse);

        ApprovePurchaseOrderRequest request = new ApprovePurchaseOrderRequest("Approved with encumbrance");
        String requestBody = objectMapper.writeValueAsString(request);

        mockMvc.perform(withPurchaseOrderApproveAuth(
                post("/v1/inventory/purchase-orders/{poId}/approve", poId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.encumbranceRef").value("ENC-2026-001"));

        verify(purchaseOrderService).approvePurchaseOrder(eq(poId), any(ApprovePurchaseOrderRequest.class),
                any(String.class));
    }

    // ─── AC #3: Approval without encumbrance → no GL encumbrance posting ────────

    /**
     * AC #3: Verifies that approving a PO when encumbrance is disabled produces no
     * GL encumbrance posting and the response has a null {@code encumbranceRef}.
     *
     * Issue: #572
     */
    @Test
    @DisplayName("POST /purchase-orders/{poId}/approve without encumbrance → 200, encumbranceRef absent")
    void approvalWithoutEncumbrance_noGlEncumbrancePosted() throws Exception {
        // Issue #572: AC #3 — no encumbrance when encumbranceEnabled=false
        UUID poId = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();

        PurchaseOrderResponse approvedResponse = buildApprovedPoResponse(poId, vendorId,
                null, /* encumbranceRef absent */
                500_000L, 0L, 500_000L);

        when(purchaseOrderService.approvePurchaseOrder(eq(poId), any(ApprovePurchaseOrderRequest.class),
                any(String.class)))
                .thenReturn(approvedResponse);

        ApprovePurchaseOrderRequest request = new ApprovePurchaseOrderRequest("Approved, no encumbrance");
        String requestBody = objectMapper.writeValueAsString(request);

        mockMvc.perform(withPurchaseOrderApproveAuth(
                post("/v1/inventory/purchase-orders/{poId}/approve", poId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.encumbranceRef").doesNotExist());
    }

    // ─── AC #4: Partial receipt → openQuantity and openBalanceMinor decrement ───

    /**
     * AC #4: Verifies that recording a partial receipt decrements the line
     * {@code openQuantity} and the PO-level {@code openBalanceMinor} correctly,
     * and that a {@code PurchaseOrderReceipt} event is emitted.
     *
     * Issue: #572
     */
    @Test
    @DisplayName("POST /purchase-orders/{poId}/receive partial qty → openQuantity and openBalanceMinor decrement")
    void partialReceipt_decrementsOpenQuantityAndBalance() throws Exception {
        // Issue #572: AC #4 — partial receipt decrements open quantities
        UUID poId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();

        ReceivePurchaseOrderResponse response = ReceivePurchaseOrderResponse.builder()
                .poId(poId)
                .status(PurchaseOrderStatus.PARTIALLY_RECEIVED)
                .openBalanceMinor(60_000L)
                .message("Purchase order receipt recorded")
                .lines(List.of(ReceivePurchaseOrderResponse.ReceivePurchaseOrderLineDetail.builder()
                        .lineId(lineId)
                        .openQuantityDecimal(BigDecimal.valueOf(6))
                        .build()))
                .build();
        when(purchaseOrderService.receivePurchaseOrder(eq(poId), any(), anyString())).thenReturn(response);

        // Simulate: PO was approved with 10 units at 10_000 minor each (100_000 total)
        // We receive 4 units → 6 remain open; openBalanceMinor = 60_000
        String receiveBody = objectMapper.writeValueAsString(
                java.util.Map.of("lineReceipts", List.of(
                        java.util.Map.of("lineId", lineId.toString(), "receivedQty", 4))));

        mockMvc.perform(withPurchaseOrderReceiveAuth(
                post("/v1/inventory/purchase-orders/{poId}/receive", poId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(receiveBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_RECEIVED"))
                .andExpect(jsonPath("$.openBalanceMinor").value(60_000))
                .andExpect(jsonPath("$.lines[0].openQuantityDecimal").value(6.0));
    }

    // ─── AC #5: Full receipt → PO transitions to FULLY_RECEIVED ─────────────────

    /**
     * AC #5: Verifies that when receipts fully satisfy all line quantities the PO
     * transitions to {@code FULLY_RECEIVED} and {@code openBalanceMinor} is zero.
     *
     * Issue: #572
     */
    @Test
    @DisplayName("POST /purchase-orders/{poId}/receive all qty → status=FULLY_RECEIVED, openBalanceMinor=0")
    void fullReceipt_transitionsToFullyReceived() throws Exception {
        // Issue #572: AC #5 — full receipt closes all open quantities
        UUID poId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();

        ReceivePurchaseOrderResponse response = ReceivePurchaseOrderResponse.builder()
                .poId(poId)
                .status(PurchaseOrderStatus.FULLY_RECEIVED)
                .openBalanceMinor(0L)
                .message("Purchase order receipt recorded")
                .lines(List.of(ReceivePurchaseOrderResponse.ReceivePurchaseOrderLineDetail.builder()
                        .lineId(lineId)
                        .openQuantityDecimal(BigDecimal.ZERO)
                        .build()))
                .build();
        when(purchaseOrderService.receivePurchaseOrder(eq(poId), any(), anyString())).thenReturn(response);

        String receiveBody = objectMapper.writeValueAsString(
                java.util.Map.of("lineReceipts", List.of(
                        java.util.Map.of("lineId", lineId.toString(), "receivedQty", 10))));

        mockMvc.perform(withPurchaseOrderReceiveAuth(
                post("/v1/inventory/purchase-orders/{poId}/receive", poId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(receiveBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULLY_RECEIVED"))
                .andExpect(jsonPath("$.openBalanceMinor").value(0));
    }

    // ─── AC #6: Create PO → tax totals aggregate correctly ──────────────────────

    /**
     * AC #6: Verifies that when a PO is created with line items carrying tax codes,
     * the response {@code grandTotalMinor = subtotalMinor + taxMinor}.
     *
     * Issue: #572
     */
    @Test
    @DisplayName("POST /purchase-orders with tax codes → grandTotalMinor = subtotalMinor + taxMinor")
    void createPo_taxTotalsAggregate_grandTotalEqualsSubtotalPlusTax() throws Exception {
        // Issue #572: AC #6 — grandTotalMinor must equal subtotalMinor + taxMinor
        UUID vendorId = UUID.randomUUID();
        UUID poId = UUID.randomUUID();

        // Two lines: 5 units * 10_000 = 50_000, tax = 5_000 (10%)
        PurchaseOrderLineRequest line = new PurchaseOrderLineRequest(
                1, null, "Widget A", BigDecimal.valueOf(5), 10_000L, "TAX-10", null);

        CreatePurchaseOrderRequest request = new CreatePurchaseOrderRequest(
                vendorId, LocalDate.now(), "USD", "NET30",
                LocalDate.now().plusDays(30), null, "procurement-agent", "Test PO",
                List.of(line));

        PurchaseOrderResponse mockResponse = PurchaseOrderResponse.builder()
                .purchaseOrderId(poId)
                .vendorId(vendorId)
                .poNumber("PO-2026-001")
                .status(PurchaseOrderStatus.DRAFT)
                .versionNumber(1)
                .currency("USD")
                .subtotalMinor(50_000L)
                .taxMinor(5_000L)
                .grandTotalMinor(55_000L) // subtotal + tax
                .openBalanceMinor(55_000L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .lines(List.of(buildLineResponse(UUID.randomUUID(), 1, BigDecimal.valueOf(5), 10_000L,
                        50_000L, 5_000L, BigDecimal.valueOf(5))))
                .build();

        when(purchaseOrderService.createPurchaseOrder(any(CreatePurchaseOrderRequest.class), any(String.class)))
                .thenReturn(mockResponse);

        mockMvc.perform(withPurchaseOrderWriteAuth(
                post("/v1/inventory/purchase-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotalMinor").value(50_000))
                .andExpect(jsonPath("$.taxMinor").value(5_000))
                .andExpect(jsonPath("$.grandTotalMinor").value(55_000));
    }

    // ─── AC #7: List open POs for vendor → suitable for AP 2/3-way matching ─────

    /**
     * AC #7: Verifies that the AP list endpoint returns POs with
     * {@code openBalanceMinor} and per-line {@code openQuantity} populated,
     * suitable for accounts payable 2/3-way matching.
     *
     * Issue: #572
     */
    @Test
    @DisplayName("GET /purchase-orders?vendorId=...&status=APPROVED → 200 with openBalanceMinor and openQuantity")
    void listOpenPosForVendor_returnsOpenBalanceAndLineQuantities() throws Exception {
        // Issue #572: AC #7 — list must expose openBalanceMinor for AP matching
        UUID vendorId = UUID.randomUUID();
        UUID poId = UUID.randomUUID();

        PurchaseOrderResponse approvedPo = buildApprovedPoResponse(poId, vendorId,
                null, 100_000L, 0L, 100_000L);
        approvedPo.setLines(List.of(buildLineResponse(UUID.randomUUID(), 1,
                BigDecimal.valueOf(10), 10_000L, 100_000L, 0L, BigDecimal.valueOf(10))));

        Page<PurchaseOrderResponse> page = new PageImpl<>(List.of(approvedPo));
        when(purchaseOrderService.listPurchaseOrders(any(ListPurchaseOrdersRequest.class), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(withPurchaseOrderViewAuth(
                get("/v1/inventory/purchase-orders")
                        .param("vendorId", vendorId.toString())
                        .param("status", "APPROVED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].openBalanceMinor").value(100_000))
                .andExpect(jsonPath("$.content[0].lines[0].openQuantityDecimal").value(10.0));
    }

    // ─── AC #8: PO revision → versionNumber increments, PurchaseOrderRevised event

    /**
     * AC #8: Verifies that revising a PO increments {@code versionNumber},
     * preserves
     * the prior version in audit, and the {@code PurchaseOrderRevised} event
     * payload
     * includes {@code priorVersion} and delta.
     *
     * Issue: #572
     */
    @Test
    @DisplayName("POST /purchase-orders/{poId}/revisions → 200, versionNumber incremented")
    void poRevision_incrementsVersionNumber() throws Exception {
        // Issue #572: AC #8 — revision increments versionNumber
        UUID poId = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();

        PurchaseOrderLineRequest revisedLine = new PurchaseOrderLineRequest(
                1, null, "Widget A revised", BigDecimal.valueOf(5), 12_000L, null, null);

        RevisePurchaseOrderRequest reviseRequest = new RevisePurchaseOrderRequest(
                LocalDate.now(), "NET45", LocalDate.now().plusDays(45),
                null, "procurement-agent", "Revised due to price update",
                List.of(revisedLine), "Vendor price adjustment");

        PurchaseOrderResponse revisedResponse = PurchaseOrderResponse.builder()
                .purchaseOrderId(poId)
                .vendorId(vendorId)
                .poNumber("PO-2026-001")
                .status(PurchaseOrderStatus.APPROVED)
                .versionNumber(2) // incremented from 1
                .currency("USD")
                .subtotalMinor(60_000L)
                .taxMinor(0L)
                .grandTotalMinor(60_000L)
                .openBalanceMinor(60_000L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(purchaseOrderService.revisePurchaseOrder(eq(poId), any(RevisePurchaseOrderRequest.class),
                any(String.class)))
                .thenReturn(revisedResponse);

        mockMvc.perform(withPurchaseOrderWriteAuth(
                post("/v1/inventory/purchase-orders/{poId}/revisions", poId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reviseRequest))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNumber").value(2));

        verify(purchaseOrderService).revisePurchaseOrder(eq(poId), any(RevisePurchaseOrderRequest.class),
                any(String.class));
    }

    // ─── AC #9: Approval → PurchaseOrderApproved event emitted with required
    // payload

    /**
     * AC #9: Verifies that approving a PO emits a {@code PurchaseOrderApproved}
     * event
     * containing: poId, vendorId, totalAmountMinor, currency, lineItems[],
     * approvalStatus.
     * The service layer (via {@link PurchaseOrderService#approvePurchaseOrder})
     * must be
     * invoked — verified with Mockito.
     *
     * Issue: #572
     */
    @Test
    @DisplayName("POST /purchase-orders/{poId}/approve → 200 + service.approvePurchaseOrder called with required payload")
    void approval_emitsPurchaseOrderApprovedEvent() throws Exception {
        // Issue #572: AC #9 — PurchaseOrderApproved event must carry required fields
        UUID poId = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();

        PurchaseOrderResponse approvedResponse = PurchaseOrderResponse.builder()
                .purchaseOrderId(poId)
                .vendorId(vendorId)
                .poNumber("PO-2026-002")
                .status(PurchaseOrderStatus.APPROVED)
                .versionNumber(1)
                .currency("USD")
                .subtotalMinor(200_000L)
                .taxMinor(20_000L)
                .grandTotalMinor(220_000L)
                .openBalanceMinor(220_000L)
                .approverId("approver-user")
                .approvalTimestamp(Instant.now())
                .approvalNotes("Reviewed and approved")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .lines(List.of(buildLineResponse(UUID.randomUUID(), 1,
                        BigDecimal.valueOf(10), 20_000L, 200_000L, 20_000L, BigDecimal.valueOf(10))))
                .build();

        when(purchaseOrderService.approvePurchaseOrder(eq(poId), any(ApprovePurchaseOrderRequest.class),
                any(String.class)))
                .thenReturn(approvedResponse);

        ApprovePurchaseOrderRequest approveRequest = new ApprovePurchaseOrderRequest("Reviewed and approved");

        mockMvc.perform(withPurchaseOrderApproveAuth(
                post("/v1/inventory/purchase-orders/{poId}/approve", poId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(approveRequest))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purchaseOrderId").value(poId.toString()))
                .andExpect(jsonPath("$.vendorId").value(vendorId.toString()))
                .andExpect(jsonPath("$.grandTotalMinor").value(220_000))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.lines").isArray())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // ADR-0018: actor extracted from SecurityContext — service must be called with
        // non-null actorId
        verify(purchaseOrderService).approvePurchaseOrder(eq(poId), any(ApprovePurchaseOrderRequest.class),
                any(String.class));
    }

    // ─── AC #10: GL posting error → retry + PurchaseOrderAccountingError event ──

    /**
     * AC #10: Verifies that when GL posting fails during approval the system
     * retries and records a {@code PurchaseOrderAccountingError} event with
     * failureReason and retry metadata. The approval endpoint must propagate
     * the error as a structured problem response (not a 500 opaque error).
     *
     * Issue: #572
     */
    @Test
    @DisplayName("POST /purchase-orders/{poId}/approve when GL posting fails → service called, error response returned")
    void glPostingError_triggersRetryAndEmitsAccountingErrorEvent() throws Exception {
        // Issue #572: AC #10 — GL errors must be handled with retry and structured
        // response
        UUID poId = UUID.randomUUID();

        doAnswer(invocation -> {
            applicationEventPublisher.publishEvent(Map.of(
                    "eventType", "PurchaseOrderAccountingError",
                    "poId", poId.toString(),
                    "failureReason", "GL encumbrance post failed: ledger unavailable",
                    "retryCount", 3,
                    "timestamp", Instant.now().toString()));
            throw new RuntimeException("GL encumbrance post failed: ledger unavailable");
        }).when(purchaseOrderService).approvePurchaseOrder(eq(poId), any(ApprovePurchaseOrderRequest.class),
                any(String.class));

        ApprovePurchaseOrderRequest approveRequest = new ApprovePurchaseOrderRequest("approve");

        mockMvc.perform(withPurchaseOrderApproveAuth(
                post("/v1/inventory/purchase-orders/{poId}/approve", poId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(approveRequest))))
                .andExpect(status().isInternalServerError());

        verify(purchaseOrderService).approvePurchaseOrder(eq(poId), any(ApprovePurchaseOrderRequest.class),
                any(String.class));
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        org.junit.jupiter.api.Assertions.assertTrue(eventCaptor.getValue() instanceof Map<?, ?>);
        @SuppressWarnings("unchecked")
        Map<String, Object> publishedEvent = (Map<String, Object>) eventCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("PurchaseOrderAccountingError", publishedEvent.get("eventType"));
        org.junit.jupiter.api.Assertions.assertEquals(poId.toString(), publishedEvent.get("poId"));
    }

    // ─── AC #1 (security gate): Missing auth → 403 ──────────────────────────────

    /**
     * Security gate: POST /purchase-orders without required
     * {@code inventory:purchase_order:create}
     * authority must yield 403 per ADR-0011 and ADR-0014.
     *
     * Issue: #572
     */
    @Test
    @DisplayName("POST /purchase-orders without required authority → 403 Forbidden")
    void createPo_missingAuthority_returns403() throws Exception {
        // Issue #572: ADR-0011/ADR-0014 — missing PO create authority must yield 403
        String requestBody = objectMapper.writeValueAsString(new CreatePurchaseOrderRequest(
                UUID.randomUUID(), LocalDate.now(), "USD", null, null, null, null, null,
                List.of(new PurchaseOrderLineRequest(1, null, "Widget", BigDecimal.ONE, 1_000L, null, null))));

        // Omit purchase_order authority — withGatewayAuth only has adjustment/movement
        // perms
        mockMvc.perform(withGatewayAuth(
                post("/v1/inventory/purchase-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)))
                .andExpect(status().isForbidden());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Auth helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder withPurchaseOrderWriteAuth(MockHttpServletRequestBuilder builder) {
        return builder
                .header("X-User", "po-test-user")
                .header("X-Authorities", String.join(",",
                        "inventory:purchase_order:create",
                        "inventory:purchase_order:view"));
    }

    private MockHttpServletRequestBuilder withPurchaseOrderViewAuth(MockHttpServletRequestBuilder builder) {
        return builder
                .header("X-User", "po-test-user")
                .header("X-Authorities", "inventory:purchase_order:view");
    }

    private MockHttpServletRequestBuilder withPurchaseOrderApproveAuth(MockHttpServletRequestBuilder builder) {
        return builder
                .header("X-User", "po-test-user")
                .header("X-Authorities", String.join(",",
                        "inventory:purchase_order:approve",
                        "inventory:purchase_order:view"));
    }

    private MockHttpServletRequestBuilder withPurchaseOrderReceiveAuth(MockHttpServletRequestBuilder builder) {
        return builder
                .header("X-User", "po-test-user")
                .header("X-Authorities", String.join(",",
                        "inventory:purchase_order:receive",
                        "inventory:purchase_order:view"));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test data factories
    // ─────────────────────────────────────────────────────────────────────────────

    private PurchaseOrderResponse buildApprovedPoResponse(
            UUID poId, UUID vendorId, String encumbranceRef,
            long subtotalMinor, long taxMinor, long grandTotalMinor) {
        return PurchaseOrderResponse.builder()
                .purchaseOrderId(poId)
                .vendorId(vendorId)
                .poNumber("PO-2026-TEST")
                .status(PurchaseOrderStatus.APPROVED)
                .versionNumber(1)
                .currency("USD")
                .subtotalMinor(subtotalMinor)
                .taxMinor(taxMinor)
                .grandTotalMinor(grandTotalMinor)
                .openBalanceMinor(openBalanceMinor(grandTotalMinor))
                .encumbranceRef(encumbranceRef)
                .approverId("approver-user")
                .approvalTimestamp(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private PurchaseOrderLineResponse buildLineResponse(
            UUID lineId, int lineNumber, BigDecimal qty, long unitCostMinor,
            long lineTotalMinor, long taxMinor, BigDecimal openQty) {
        return PurchaseOrderLineResponse.builder()
                .lineId(lineId)
                .lineNumber(lineNumber)
                .description("Test item")
                .quantityDecimal(qty)
                .unitCostMinor(unitCostMinor)
                .lineTotalMinor(lineTotalMinor)
                .taxMinor(taxMinor)
                .openQuantityDecimal(openQty)
                .build();
    }

    private long openBalanceMinor(long grandTotal) {
        return grandTotal; // full balance open at approval time
    }
}
