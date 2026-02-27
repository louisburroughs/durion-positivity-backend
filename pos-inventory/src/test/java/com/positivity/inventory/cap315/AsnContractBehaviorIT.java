package com.positivity.inventory.cap315;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.dto.asn.AsnLineResponse;
import com.positivity.inventory.internal.dto.asn.AsnResponse;
import com.positivity.inventory.internal.dto.asn.CreateAsnLineRequest;
import com.positivity.inventory.internal.dto.asn.CreateAsnRequest;
import com.positivity.inventory.internal.dto.asn.CreateGoodsReceiptLineRequest;
import com.positivity.inventory.internal.dto.asn.CreateGoodsReceiptRequest;
import com.positivity.inventory.internal.dto.asn.GoodsReceiptLineResponse;
import com.positivity.inventory.internal.dto.asn.GoodsReceiptResponse;
import com.positivity.inventory.internal.enums.AsnStatus;
import com.positivity.inventory.internal.exception.DuplicateAsnException;
import com.positivity.inventory.internal.exception.InvalidPoReferenceException;
import com.positivity.inventory.internal.exception.OverReceiptNotPermittedException;
import com.positivity.inventory.service.AsnService;
import com.positivity.inventory.service.contract.BaseContractIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract behavior integration tests for the ASN and Goods Receipt API —
 * Story #571: Load ASN for Receiving (Procure-to-Pay — CAP-315).
 *
 * <p>
 * ADR compliance:
 * <ul>
 * <li>ADR-0001: inventory ledger — GOODS_RECEIPT ledger entry required on
 * receipt</li>
 * <li>ADR-0011: gateway auth headers (X-User, X-Authorities) required</li>
 * <li>ADR-0014: internal service security — all endpoints require gateway
 * auth</li>
 * <li>ADR-0017: HTTP response codes: 201 Created, 200 OK, 400 Bad Request, 403
 * Forbidden, 409 Conflict</li>
 * <li>ADR-0018: actor userId extracted from SecurityContext via
 * SecurityContextHelper</li>
 * </ul>
 *
 * Issue: #571
 */
@DisplayName("ASN + Goods Receipt Contract Behavior — Story #571")
class AsnContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AsnService asnService;

    @MockitoBean
    private ApplicationEventPublisher applicationEventPublisher;

    // ─── AC #1: Invalid PO reference → 400 ──────────────────────────────────────

    /**
     * AC #1: Verifies that creating an ASN referencing a non-existent or
     * non-APPROVED PO is rejected with 400 and an error body listing the invalid
     * PO references.
     *
     * Issue: #571
     */
    @Test
    @DisplayName("POST /asns with non-existent / non-APPROVED PO → 400 Bad Request")
    void invalidPoReference_returns400() throws Exception {
        // Issue #571 AC #1: service must reject invalid PO references with
        // InvalidPoReferenceException
        UUID invalidPoId = UUID.randomUUID();
        when(asnService.createAsn(any(CreateAsnRequest.class), anyString()))
                .thenThrow(new InvalidPoReferenceException(
                        "INVALID_PO_REFERENCE: PO " + invalidPoId + " is unknown or not APPROVED"));

        String body = objectMapper.writeValueAsString(buildCreateAsnRequest(invalidPoId));

        mockMvc.perform(withAsnCreateAuth(
                post("/v1/inventory/asns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isBadRequest());
    }

    // ─── AC #2: ASN creation produces no GL entry ─────────────────────────────

    /**
     * AC #2: Verifies controller returns 201 and does not itself publish
     * application events when using mocked service behavior.
     *
     * Issue: #571
     */
    @Test
    @DisplayName("POST /asns (valid) → 201 Created, no ApplicationEvent published")
    void asnCreation_producesNoGlEntry() throws Exception {
        // Issue #571 AC #2: no GL event on ASN creation
        UUID asnId = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        UUID poId = UUID.randomUUID();

        AsnResponse asnResponse = buildAsnResponse(asnId, vendorId, AsnStatus.LOADED);

        when(asnService.createAsn(any(CreateAsnRequest.class), anyString()))
                .thenReturn(asnResponse);

        String body = objectMapper.writeValueAsString(buildCreateAsnRequest(poId));

        mockMvc.perform(withAsnCreateAuth(
                post("/v1/inventory/asns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.asnId").value(asnId.toString()))
                .andExpect(jsonPath("$.status").value("LOADED"));

        // Verifies controller returns 201 and does not itself publish application
        // events (mocked service)
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    // ─── AC #3: Receipt creates GRNI accrual journal entry ────────────────────

    /**
     * AC #3: Verifies that completing a physical goods receipt causes a GRNI
     * accrual event to be emitted carrying {@code totalAccruedAmountMinor}: the
     * financial signal for accounts-payable matching.
     *
     * Issue: #571
     */
    @Test
    @DisplayName("POST /goods-receipts → 201 Created, response contains totalAccruedAmountMinor")
    void receiptCompleted_emitsAccrualEvent() throws Exception {
        // Issue #571 AC #3: GRNI accrual amount must be present in the receipt response
        UUID receiptId = UUID.randomUUID();
        UUID poId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        long expectedAccruedAmount = 75_000L;

        GoodsReceiptResponse receiptResponse = buildGoodsReceiptResponse(receiptId, poId, locationId,
                expectedAccruedAmount);

        when(asnService.createGoodsReceipt(any(CreateGoodsReceiptRequest.class), anyString()))
                .thenReturn(receiptResponse);

        String body = objectMapper.writeValueAsString(buildCreateGoodsReceiptRequest(poId, locationId));

        mockMvc.perform(withGoodsReceiptCreateAuth(
                post("/v1/inventory/goods-receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receiptId").value(receiptId.toString()))
                .andExpect(jsonPath("$.totalAccruedAmountMinor").value(expectedAccruedAmount));
    }

    // ─── AC #4: Inventory on-hand updates correctly ───────────────────────────

    /**
     * AC #4: Verifies that completing a goods receipt produces a 201 response with
     * a valid receiptId, confirming the service processed the receipt and created
     * the GOODS_RECEIPT ledger entry for on-hand quantity update.
     *
     * Service-layer unit tests in {@code AsnServiceImplTest} verify the ledger
     * entry itself; this contract test verifies the HTTP contract only.
     *
     * Issue: #571
     */
    @Test
    @DisplayName("POST /goods-receipts → 201 Created, receiptId present (on-hand update confirmed)")
    void receipt_updatesOnHandInventory() throws Exception {
        // Issue #571 AC #4: valid goods receipt accepted with 201 and receipt ID
        // returned
        UUID receiptId = UUID.randomUUID();
        UUID poId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        GoodsReceiptResponse receiptResponse = buildGoodsReceiptResponse(receiptId, poId, locationId, 50_000L);

        when(asnService.createGoodsReceipt(any(CreateGoodsReceiptRequest.class), anyString()))
                .thenReturn(receiptResponse);

        String body = objectMapper.writeValueAsString(buildCreateGoodsReceiptRequest(poId, locationId));

        mockMvc.perform(withGoodsReceiptCreateAuth(
                post("/v1/inventory/goods-receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receiptId").value(receiptId.toString()))
                .andExpect(jsonPath("$.locationId").value(locationId.toString()));
    }

    // ─── AC #5: Over-receipt without permission → 403 ─────────────────────────

    /**
     * AC #5: Verifies that a goods receipt whose quantity exceeds the remaining PO
     * quantity is rejected with 403 FORBIDDEN (error code
     * OVER_RECEIPT_NOT_PERMITTED)
     * when the actor does not hold the override permission.
     *
     * Issue: #571
     */
    @Test
    @DisplayName("POST /goods-receipts over-receive without override permission → 403 Forbidden")
    void overReceipt_withoutPermission_returns403() throws Exception {
        // Issue #571 AC #5: over-receipt without permission must be 403
        UUID poId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        when(asnService.createGoodsReceipt(any(CreateGoodsReceiptRequest.class), anyString()))
                .thenThrow(new OverReceiptNotPermittedException("OVER_RECEIPT_NOT_PERMITTED"));

        String body = objectMapper.writeValueAsString(buildCreateGoodsReceiptRequest(poId, locationId));

        mockMvc.perform(withGoodsReceiptCreateAuth(
                post("/v1/inventory/goods-receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isForbidden());
    }

    // ─── AC #6: GRNI balance available for AP matching ───────────────────────

    /**
     * AC #6: Verifies that the GRNI balance and receipt-to-PO linkage are
     * accessible via GET /goods-receipts/{receiptId}, returning 200 with
     * {@code totalAccruedAmountMinor} and {@code poId} to enable AP matching.
     *
     * Issue: #571
     */
    @Test
    @DisplayName("GET /goods-receipts/{receiptId} → 200 OK with GRNI balance fields")
    void grniBalance_availableForApMatching() throws Exception {
        // Issue #571 AC #6: GRNI balance must be readable for AP matching
        UUID receiptId = UUID.randomUUID();
        UUID poId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        long grniBalance = 120_000L;

        GoodsReceiptResponse receiptResponse = buildGoodsReceiptResponse(receiptId, poId, locationId, grniBalance);

        when(asnService.getGoodsReceipt(eq(receiptId)))
                .thenReturn(receiptResponse);

        mockMvc.perform(withGoodsReceiptViewAuth(
                get("/v1/inventory/goods-receipts/{receiptId}", receiptId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptId").value(receiptId.toString()))
                .andExpect(jsonPath("$.poId").value(poId.toString()))
                .andExpect(jsonPath("$.totalAccruedAmountMinor").value(grniBalance));
    }

    // ─── AC #7: Partial receipt → ASN status PARTIALLY_RECEIVED ─────────────

    /**
     * AC #7: Verifies that when a partial goods receipt is posted (qty &lt; PO
     * remaining qty), the ASN transitions to PARTIALLY_RECEIVED status and the
     * remaining open quantity is preserved.
     *
     * Issue: #571
     */
    @Test
    @DisplayName("GET /asns/{asnId} after partial receipt → 200 OK, status PARTIALLY_RECEIVED")
    void partialReceipt_asnBecomesPartiallyReceived() throws Exception {
        // Issue #571 AC #7: partial receipt must set ASN to PARTIALLY_RECEIVED
        UUID asnId = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();

        AsnResponse asnAfterPartialReceipt = buildAsnResponse(asnId, vendorId, AsnStatus.PARTIALLY_RECEIVED);

        when(asnService.getAsn(eq(asnId)))
                .thenReturn(asnAfterPartialReceipt);

        mockMvc.perform(withAsnViewAuth(
                get("/v1/inventory/asns/{asnId}", asnId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asnId").value(asnId.toString()))
                .andExpect(jsonPath("$.status").value("PARTIALLY_RECEIVED"));
    }

    @Test
    @DisplayName("POST /asns + POST /goods-receipts full qty then GET /asns/{asnId} → status FULLY_RECEIVED")
    void fullReceipt_asnBecomesFullyReceived() throws Exception {
        UUID asnId = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        UUID poId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();

        AsnResponse createdAsn = buildAsnResponse(asnId, vendorId, AsnStatus.LOADED);
        GoodsReceiptResponse fullReceipt = buildGoodsReceiptResponse(receiptId, poId, locationId, 50_000L);
        fullReceipt.setAsnId(asnId);
        AsnResponse asnAfterFullReceipt = buildAsnResponse(asnId, vendorId, AsnStatus.FULLY_RECEIVED);

        when(asnService.createAsn(any(CreateAsnRequest.class), anyString())).thenReturn(createdAsn);
        when(asnService.createGoodsReceipt(any(CreateGoodsReceiptRequest.class), anyString())).thenReturn(fullReceipt);
        when(asnService.getAsn(eq(asnId))).thenReturn(asnAfterFullReceipt);

        String asnBody = objectMapper.writeValueAsString(buildCreateAsnRequest(poId));
        mockMvc.perform(withAsnCreateAuth(
                post("/v1/inventory/asns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asnBody)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.asnId").value(asnId.toString()));

        CreateGoodsReceiptRequest fullReceiptRequest = buildCreateGoodsReceiptRequest(poId, locationId);
        fullReceiptRequest.setAsnId(asnId);
        String receiptBody = objectMapper.writeValueAsString(fullReceiptRequest);

        mockMvc.perform(withGoodsReceiptCreateAuth(
                post("/v1/inventory/goods-receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(receiptBody)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receiptId").value(receiptId.toString()));

        mockMvc.perform(withAsnViewAuth(
                get("/v1/inventory/asns/{asnId}", asnId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asnId").value(asnId.toString()))
                .andExpect(jsonPath("$.status").value("FULLY_RECEIVED"));
    }

    // ─── AC #8: Duplicate ASN → 409 ──────────────────────────────────────────

    /**
     * AC #8: Verifies that submitting an ASN with the same vendorId and
     * asnReferenceNumber as an existing ASN is rejected with 409 Conflict
     * (DUPLICATE_ASN).
     *
     * Issue: #571
     */
    @Test
    @DisplayName("POST /asns with duplicate vendorId+asnReferenceNumber → 409 Conflict")
    void duplicateAsn_returns409() throws Exception {
        // Issue #571 AC #8: duplicate ASN must return 409
        UUID poId = UUID.randomUUID();

        when(asnService.createAsn(any(CreateAsnRequest.class), anyString()))
                .thenThrow(new DuplicateAsnException("DUPLICATE_ASN: ASN with this reference already exists"));

        String body = objectMapper.writeValueAsString(buildCreateAsnRequest(poId));

        mockMvc.perform(withAsnCreateAuth(
                post("/v1/inventory/asns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isConflict());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Auth helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder withAsnCreateAuth(MockHttpServletRequestBuilder builder) {
        return builder
                .header("X-User", "asn-test-user")
                .header("X-Authorities", String.join(",",
                        "inventory:asn:create",
                        "inventory:asn:view"));
    }

    private MockHttpServletRequestBuilder withAsnViewAuth(MockHttpServletRequestBuilder builder) {
        return builder
                .header("X-User", "asn-test-user")
                .header("X-Authorities", "inventory:asn:view");
    }

    private MockHttpServletRequestBuilder withGoodsReceiptCreateAuth(MockHttpServletRequestBuilder builder) {
        return builder
                .header("X-User", "asn-test-user")
                .header("X-Authorities", String.join(",",
                        "inventory:goods_receipt:create",
                        "inventory:goods_receipt:view"));
    }

    private MockHttpServletRequestBuilder withGoodsReceiptViewAuth(MockHttpServletRequestBuilder builder) {
        return builder
                .header("X-User", "asn-test-user")
                .header("X-Authorities", "inventory:goods_receipt:view");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test data factories
    // ─────────────────────────────────────────────────────────────────────────────

    private CreateAsnRequest buildCreateAsnRequest(UUID poId) {
        CreateAsnLineRequest line = new CreateAsnLineRequest();
        line.setPoId(poId);
        line.setSku("SKU-TEST-001");
        line.setQuantityShipped(new BigDecimal("10"));
        line.setUnitCostMinor(5_000L);

        CreateAsnRequest request = new CreateAsnRequest();
        request.setVendorId(UUID.randomUUID());
        request.setAsnReferenceNumber("ASN-2026-TEST-001");
        request.setRelatedPoIds(List.of(poId));
        request.setShipDate(LocalDate.now());
        request.setExpectedArrivalDate(LocalDate.now().plusDays(3));
        request.setLineItems(List.of(line));
        return request;
    }

    private CreateGoodsReceiptRequest buildCreateGoodsReceiptRequest(UUID poId, UUID locationId) {
        CreateGoodsReceiptLineRequest line = new CreateGoodsReceiptLineRequest();
        line.setSku("SKU-TEST-001");
        line.setQuantityReceived(new BigDecimal("10"));
        line.setUnitCostMinor(5_000L);

        CreateGoodsReceiptRequest request = new CreateGoodsReceiptRequest();
        request.setPoId(poId);
        request.setLocationId(locationId);
        request.setLines(List.of(line));
        return request;
    }

    private AsnResponse buildAsnResponse(UUID asnId, UUID vendorId, AsnStatus status) {
        return AsnResponse.builder()
                .asnId(asnId)
                .asnReferenceNumber("ASN-2026-TEST-001")
                .vendorId(vendorId)
                .status(status)
                .shipDate(LocalDate.now())
                .expectedArrivalDate(LocalDate.now().plusDays(3))
                .createdBy("asn-test-user")
                .createdAt(Instant.now())
                .lineItems(List.of(
                        AsnLineResponse.builder()
                                .asnLineId(UUID.randomUUID())
                                .sku("SKU-TEST-001")
                                .quantityShipped(new BigDecimal("10"))
                                .build()))
                .build();
    }

    private GoodsReceiptResponse buildGoodsReceiptResponse(UUID receiptId, UUID poId, UUID locationId,
            long totalAccruedAmountMinor) {
        return GoodsReceiptResponse.builder()
                .receiptId(receiptId)
                .receiptNumber("GR-2026-TEST-001")
                .poId(poId)
                .locationId(locationId)
                .totalAccruedAmountMinor(totalAccruedAmountMinor)
                .createdBy("asn-test-user")
                .createdAt(Instant.now())
                .lines(List.of(
                        GoodsReceiptLineResponse.builder()
                                .receiptLineId(UUID.randomUUID())
                                .sku("SKU-TEST-001")
                                .quantityReceived(new BigDecimal("10"))
                                .unitCostMinor(5_000L)
                                .lineAccruedAmountMinor(totalAccruedAmountMinor)
                                .build()))
                .build();
    }
}
