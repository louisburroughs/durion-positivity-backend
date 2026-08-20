package com.positivity.inventory.contract;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.service.LedgerPostingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Contract IT for point-in-time (as-of) on-hand queries (odoo-parity A3, issue #1029):
 * historical correctness across a movement sequence, the additional
 * {@code inventory:ledger:view} permission gate, future-date 422, and malformed-instant 400.
 */
@DisplayName("As-Of On-Hand Contract Behavior")
class AsOfOnHandContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    @Autowired
    private InventoryStockSummaryRepository inventoryStockSummaryRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @BeforeEach
    void setUp() {
        inventoryLedgerEntryRepository.deleteAll();
        inventoryStockSummaryRepository.deleteAll();
    }

    /** Auth with on-hand read access but WITHOUT {@code inventory:ledger:view}. */
    private MockHttpServletRequestBuilder withOnHandOnlyAuth(MockHttpServletRequestBuilder requestBuilder) {
        return requestBuilder
                .header("X-User", "contract-test-user")
                .header("X-Authorities", "inventory:on_hand:view,inventory:on_hand:search");
    }

    @Test
    @DisplayName("AC-1: on-hand is correct at probe instants before, mid, and after a movement sequence")
    void onHandCorrectAtProbeInstants() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID location = UUID.randomUUID();

        post(productId, location, InventoryLedgerEventType.GOODS_RECEIPT, new BigDecimal("10"));
        Thread.sleep(50);
        post(productId, location, InventoryLedgerEventType.GOODS_ISSUE, new BigDecimal("-4"));
        Thread.sleep(50);
        post(productId, location, InventoryLedgerEventType.GOODS_RECEIPT, new BigDecimal("7"));

        List<InventoryLedgerEntry> entries =
                inventoryLedgerEntryRepository.findByStockItemIdOrderByTimestampAsc(productId.toString());
        Instant tReceipt = entries.get(0).getTimestamp();
        Instant tIssue = entries.get(1).getTimestamp();
        Instant tSecondReceipt = entries.get(2).getTimestamp();

        // Before any movement: no location rows exist yet.
        mockMvc.perform(withGatewayAuth(get("/v1/inventory/availability/{productId}", productId)
                        .param("asOf", tReceipt.minusMillis(1).toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // At the first receipt (inclusive bound): 10.
        mockMvc.perform(withGatewayAuth(
                        get("/v1/inventory/availability/{productId}", productId).param("asOf", tReceipt.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].onHandQuantity").value(10))
                // On-hand only: ATP and forecast fields are null for as-of requests.
                .andExpect(jsonPath("$[0].availableToPromiseQuantity").value(nullValue()))
                .andExpect(jsonPath("$[0].incomingQty").value(nullValue()))
                .andExpect(jsonPath("$[0].outgoingQty").value(nullValue()))
                .andExpect(jsonPath("$[0].projectedAvailable").value(nullValue()));

        // Mid-sequence, after the issue but before the second receipt: 6.
        mockMvc.perform(withGatewayAuth(get("/v1/inventory/availability/{productId}", productId)
                        .param("asOf", tSecondReceipt.minusMillis(1).toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].onHandQuantity").value(6));

        // After everything: 13 — and non-as-of reads still serve the same total from the summary.
        mockMvc.perform(withGatewayAuth(get("/v1/inventory/availability/{productId}", productId)
                        .param("asOf", tSecondReceipt.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].onHandQuantity").value(13));
        mockMvc.perform(withGatewayAuth(get("/v1/inventory/availability/{productId}", productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].onHandQuantity").value(13));

        // Sanity: probe ordering really was strict.
        org.assertj.core.api.Assertions.assertThat(tReceipt).isBefore(tIssue);
        org.assertj.core.api.Assertions.assertThat(tIssue).isBefore(tSecondReceipt);
    }

    @Test
    @DisplayName("AC-2: location-inquiry endpoints honor asOf and return on-hand only")
    void locationInquiryHonorsAsOf() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID location = UUID.randomUUID();

        post(productId, location, InventoryLedgerEventType.GOODS_RECEIPT, new BigDecimal("9"));
        Thread.sleep(50);
        post(productId, location, InventoryLedgerEventType.GOODS_ISSUE, new BigDecimal("-9"));

        List<InventoryLedgerEntry> entries =
                inventoryLedgerEntryRepository.findByStockItemIdOrderByTimestampAsc(productId.toString());
        Instant tReceipt = entries.get(0).getTimestamp();

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/locations/{locationId}/inventory-inquiry", location)
                        .param("asOf", tReceipt.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onHandQuantity").value(9))
                .andExpect(jsonPath("$.availableToPromiseQuantity").value(nullValue()));

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/locations/{locationId}/inventory-items", location)
                        .param("asOf", tReceipt.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].stockItemId").value(productId.toString()))
                .andExpect(jsonPath("$.items[0].onHandQuantity").value(9));

        // Current view: the item was fully issued, so the historical row is gone.
        mockMvc.perform(withGatewayAuth(get("/v1/inventory/locations/{locationId}/inventory-items", location)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    @DisplayName("AC-3: asOf requires inventory:ledger:view — 403 with on-hand-only authority, 200 without asOf")
    void asOfRequiresLedgerViewAuthority() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        post(productId, location, InventoryLedgerEventType.GOODS_RECEIPT, new BigDecimal("5"));

        String pastInstant = Instant.now().minusSeconds(1).toString();

        mockMvc.perform(withOnHandOnlyAuth(
                        get("/v1/inventory/availability/{productId}", productId).param("asOf", pastInstant)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(withOnHandOnlyAuth(get("/v1/inventory/availability/{productId}", productId)))
                .andExpect(status().isOk());

        mockMvc.perform(withOnHandOnlyAuth(get("/v1/inventory/locations/{locationId}/inventory-inquiry", location)
                        .param("asOf", pastInstant)))
                .andExpect(status().isForbidden());

        mockMvc.perform(withOnHandOnlyAuth(get("/v1/inventory/locations/{locationId}/inventory-items", location)
                        .param("asOf", pastInstant)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-4: future-dated asOf yields a deterministic 422; malformed asOf yields 400 ApiError")
    void asOfValidation() throws Exception {
        UUID productId = UUID.randomUUID();

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/availability/{productId}", productId)
                        .param("asOf", Instant.now().plusSeconds(3600).toString())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AS_OF_IN_FUTURE"))
                .andExpect(jsonPath("$.status").value(422));

        mockMvc.perform(withGatewayAuth(
                        get("/v1/inventory/availability/{productId}", productId).param("asOf", "not-an-instant")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/availability/{productId}", productId)
                        .param("asOf", Instant.now().minusSeconds(1).toString())
                        .param("horizon", Instant.now().toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM_COMBINATION"));

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/locations/{locationId}/inventory-inquiry", UUID.randomUUID())
                        .param("asOf", Instant.now().plusSeconds(3600).toString())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AS_OF_IN_FUTURE"));
    }

    private void post(UUID productId, UUID locationId, InventoryLedgerEventType eventType, BigDecimal change) {
        ledgerPostingService.post(InventoryLedgerEntry.builder()
                .stockItemId(productId.toString())
                .locationId(locationId)
                .eventType(eventType)
                .changeInQuantity(change)
                .quantityAfter(new BigDecimal("0"))
                .transactionUserId("asof-contract-seed")
                .build());
    }
}
