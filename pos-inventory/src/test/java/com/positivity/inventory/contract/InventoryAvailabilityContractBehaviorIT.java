package com.positivity.inventory.contract;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// Issue CAP-170: Place contract behavior test in ..service.. namespace to
// satisfy repository access architecture rule for test seeding.
@DisplayName("Inventory Availability Contract Behavior")
class InventoryAvailabilityContractBehaviorIT extends BaseContractIntegrationTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static final UUID LOC_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LOC_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID LOC_ATP = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    @BeforeEach
    void setUp() {
        inventoryLedgerEntryRepository.deleteAll();
    }

    @Test
    @DisplayName("AC-1: getAvailability_returnsPerLocationList_whenProductHasStock")
    void getAvailability_returnsPerLocationList_whenProductHasStock() throws Exception {
        UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        seedOnHand(productId, LOC_1, 40);
        seedOnHand(productId, LOC_2, 60);

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/availability/{productId}", productId)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].locationId").exists())
                .andExpect(jsonPath("$[0].locationName").exists())
                .andExpect(jsonPath("$[0].onHandQuantity").exists())
                .andExpect(jsonPath("$[0].availableToPromiseQuantity").exists())
                .andExpect(jsonPath("$[1].locationId").exists())
                .andExpect(jsonPath("$[1].locationName").exists())
                .andExpect(jsonPath("$[1].onHandQuantity").exists())
                .andExpect(jsonPath("$[1].availableToPromiseQuantity").exists());
    }

    @Test
    @DisplayName("AC-2: getAvailability_returnsEmptyList_whenProductHasNoStock")
    void getAvailability_returnsEmptyList_whenProductHasNoStock() throws Exception {
        UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/availability/{productId}", productId)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("AC-3: getAvailability_returnsEmptyList_whenProductNotFoundInLedger")
    void getAvailability_returnsEmptyList_whenProductNotFoundInLedger() throws Exception {
        UUID nonExistentProductId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/availability/{productId}", nonExistentProductId)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("AC-4: getAvailability_returns400_whenProductIdMalformed")
    void getAvailability_returns400_whenProductIdMalformed() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/inventory/availability/{productId}", "not-a-uuid")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-5: getAvailability_calculatesAtp_excludingInactiveReservations")
    void getAvailability_calculatesAtp_excludingInactiveReservations() throws Exception {
        UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        seedOnHand(productId, LOC_ATP, 100);

        seedReservationCreated(productId, LOC_ATP, 30);

        seedReservationCreated(productId, LOC_ATP, 20);
        seedReservationReleased(productId, LOC_ATP, 20);

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/availability/{productId}", productId)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].onHandQuantity").value(100))
                .andExpect(jsonPath("$[0].availableToPromiseQuantity").value(70));
    }

    private void seedOnHand(UUID productId, UUID locationId, int quantity) {
        inventoryLedgerEntryRepository.save(InventoryLedgerEntry.builder()
                .stockItemId(productId.toString())
                .locationId(locationId)
                .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                .changeInQuantity(quantity)
                .quantityAfter(quantity)
                .transactionUserId("contract-seed")
                .timestamp(Instant.now(TEST_CLOCK))
                .notes("seed on-hand for " + locationId)
                .build());
    }

    private void seedReservationCreated(UUID productId, UUID locationId, int quantity) {
        inventoryLedgerEntryRepository.save(InventoryLedgerEntry.builder()
                .stockItemId(productId.toString())
                .locationId(locationId)
                .eventType(InventoryLedgerEventType.RESERVATION_CREATED)
                .changeInQuantity(quantity)
                .quantityAfter(0)
                .transactionUserId("contract-seed")
                .timestamp(Instant.now(TEST_CLOCK))
                .notes("seed active reservation")
                .build());
    }

    private void seedReservationReleased(UUID productId, UUID locationId, int quantity) {
        inventoryLedgerEntryRepository.save(InventoryLedgerEntry.builder()
                .stockItemId(productId.toString())
                .locationId(locationId)
                .eventType(InventoryLedgerEventType.RESERVATION_RELEASED)
                .changeInQuantity(quantity)
                .quantityAfter(0)
                .transactionUserId("contract-seed")
                .timestamp(Instant.now(TEST_CLOCK))
                .notes("seed released reservation")
                .build());
    }
}
