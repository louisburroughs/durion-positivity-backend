package com.positivity.inventory.service.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;

/**
 * Contract behavior tests for GET /v1/inventory/availability/query.
 *
 * <p>
 * Verifies ATP query by productSku + locationId (+ optional storageLocationId)
 * as defined in Story #36 of CAP-215.
 *
 * <p>
 * All tests are in RED state until the endpoint and service are implemented
 * (GREEN step).
 *
 * Issue: CAP-215
 */
@DisplayName("Inventory Availability Query Contract Behavior")
class InventoryAvailabilityQueryContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    @BeforeEach
    void setUp() {
        inventoryLedgerEntryRepository.deleteAll();
    }

    // Issue CAP-215: AC-1 — on-hand and ATP returned correctly when stock exists
    @Test
    @DisplayName("AC-1: queryAvailability_returns200_withCorrectOnHandAndAtp")
    void queryAvailability_returns200_withCorrectOnHandAndAtp() throws Exception {
        seedGoodsReceipt("SKU-TEST-1", "LOC-ALPHA", 100);
        seedAllocationCreated("SKU-TEST-1", "LOC-ALPHA", 20);

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/availability/query")
                .param("productSku", "SKU-TEST-1")
                .param("locationId", "LOC-ALPHA")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.productSku").value("SKU-TEST-1"))
                .andExpect(jsonPath("$.locationId").value("LOC-ALPHA"))
                .andExpect(jsonPath("$.onHandQuantity").value(100))
                .andExpect(jsonPath("$.allocatedQuantity").value(20))
                .andExpect(jsonPath("$.availableToPromiseQuantity").value(80))
                .andExpect(jsonPath("$.unitOfMeasure").exists());
    }

    // Issue CAP-215: AC-2 — known product with no stock at queried location returns
    // 200 with zeros
    @Test
    @DisplayName("AC-2: queryAvailability_returnsZeroQuantities_whenProductKnownButNoStockAtLocation")
    void queryAvailability_returnsZeroQuantities_whenProductKnownButNoStockAtLocation() throws Exception {
        // Seed an entry for SKU-ZERO at a DIFFERENT location so the product is
        // "known" in the system; the queried location has no stock.
        seedGoodsReceipt("SKU-ZERO", "LOC-OTHER", 50);

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/availability/query")
                .param("productSku", "SKU-ZERO")
                .param("locationId", "LOC-ZERO")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.productSku").value("SKU-ZERO"))
                .andExpect(jsonPath("$.locationId").value("LOC-ZERO"))
                .andExpect(jsonPath("$.onHandQuantity").value(0))
                .andExpect(jsonPath("$.allocatedQuantity").value(0))
                .andExpect(jsonPath("$.availableToPromiseQuantity").value(0));
    }

    // Issue CAP-215: AC-3 — unknown productSku returns 404 with structured error
    // body
    @Test
    @DisplayName("AC-3: queryAvailability_returns404_whenProductSkuNotFound")
    void queryAvailability_returns404_whenProductSkuNotFound() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/inventory/availability/query")
                .param("productSku", "NONEXISTENT-SKU")
                .param("locationId", "LOC-ANY")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // Issue CAP-215: AC-4 — all entries for a locationId are aggregated when no
    // storageLocationId is given
    @Test
    @DisplayName("AC-4: queryAvailability_aggregatesAllStorageLocations_whenNoStorageLocationId")
    void queryAvailability_aggregatesAllStorageLocations_whenNoStorageLocationId() throws Exception {
        seedGoodsReceipt("SKU-BETA", "LOC-BETA", 40);
        seedGoodsReceipt("SKU-BETA", "LOC-BETA", 60);

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/availability/query")
                .param("productSku", "SKU-BETA")
                .param("locationId", "LOC-BETA")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.productSku").value("SKU-BETA"))
                .andExpect(jsonPath("$.locationId").value("LOC-BETA"))
                .andExpect(jsonPath("$.onHandQuantity").value(100))
                .andExpect(jsonPath("$.storageLocationId").isEmpty());
    }

    // Issue CAP-215: AC-5 — per ADR-0001 only ALLOCATION events affect
    // allocatedQty/ATP,
    // RESERVATION events must NOT reduce allocatedQuantity or ATP
    @Test
    @DisplayName("AC-5: queryAvailability_atpReflectsAllocations_notReservations")
    void queryAvailability_atpReflectsAllocations_notReservations() throws Exception {
        seedGoodsReceipt("SKU-ATP", "LOC-GAMMA", 200);
        seedAllocationCreated("SKU-ATP", "LOC-GAMMA", 50);
        seedAllocationReleased("SKU-ATP", "LOC-GAMMA", 10);
        // RESERVATION_CREATED must NOT count toward allocatedQuantity or ATP
        seedReservationCreated("SKU-ATP", "LOC-GAMMA", 30);

        // allocatedQuantity = 50 (ALLOCATION_CREATED) - 10 (ALLOCATION_RELEASED) = 40
        // ATP = 200 (onHand) - 40 (allocated) = 160
        mockMvc.perform(withGatewayAuth(get("/v1/inventory/availability/query")
                .param("productSku", "SKU-ATP")
                .param("locationId", "LOC-GAMMA")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.onHandQuantity").value(200))
                .andExpect(jsonPath("$.allocatedQuantity").value(40))
                .andExpect(jsonPath("$.availableToPromiseQuantity").value(160));
    }

    // -------------------------------------------------------------------------
    // Seed helpers
    // -------------------------------------------------------------------------

    private void seedGoodsReceipt(String productSku, String locationId, int quantity) {
        inventoryLedgerEntryRepository.save(InventoryLedgerEntry.builder()
                .stockItemId(productSku)
                .locationId(locationId)
                .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                .changeInQuantity(quantity)
                .quantityAfter(quantity)
                .transactionUserId("contract-seed")
                .timestamp(Instant.now())
                .notes("seed goods receipt for " + locationId)
                .build());
    }

    private void seedAllocationCreated(String productSku, String locationId, int quantity) {
        inventoryLedgerEntryRepository.save(InventoryLedgerEntry.builder()
                .stockItemId(productSku)
                .locationId(locationId)
                .eventType(InventoryLedgerEventType.ALLOCATION_CREATED)
                .changeInQuantity(quantity)
                .quantityAfter(0)
                .transactionUserId("contract-seed")
                .timestamp(Instant.now())
                .notes("seed allocation created")
                .build());
    }

    private void seedAllocationReleased(String productSku, String locationId, int quantity) {
        inventoryLedgerEntryRepository.save(InventoryLedgerEntry.builder()
                .stockItemId(productSku)
                .locationId(locationId)
                .eventType(InventoryLedgerEventType.ALLOCATION_RELEASED)
                .changeInQuantity(quantity)
                .quantityAfter(0)
                .transactionUserId("contract-seed")
                .timestamp(Instant.now())
                .notes("seed allocation released")
                .build());
    }

    private void seedReservationCreated(String productSku, String locationId, int quantity) {
        inventoryLedgerEntryRepository.save(InventoryLedgerEntry.builder()
                .stockItemId(productSku)
                .locationId(locationId)
                .eventType(InventoryLedgerEventType.RESERVATION_CREATED)
                .changeInQuantity(quantity)
                .quantityAfter(0)
                .transactionUserId("contract-seed")
                .timestamp(Instant.now())
                .notes("seed reservation (must not affect allocated qty per ADR-0001)")
                .build());
    }
}
