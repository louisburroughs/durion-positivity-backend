package com.positivity.inventory.contract;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.ReservationEntity;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.ReservationStatus;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.ReservationRepository;
import com.positivity.inventory.internal.service.LedgerPostingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contract IT for the additive forecast fields on the availability endpoints (odoo-parity A2,
 * issue #1028): incomingQty / outgoingQty / projectedAvailable appear, existing fields keep
 * their semantics, and the optional {@code horizon} parameter bounds the forecast.
 */
@DisplayName("Forecast Availability Contract Behavior")
class ForecastAvailabilityContractBehaviorIT extends BaseContractIntegrationTest {

    private static final UUID SITE = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    @Autowired
    private InventoryStockSummaryRepository inventoryStockSummaryRepository;

    @Autowired
    private com.positivity.inventory.internal.service.PurchaseOrderProjectionTestSupport projection;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @BeforeEach
    void setUp() {
        inventoryLedgerEntryRepository.deleteAll();
        inventoryStockSummaryRepository.deleteAll();
    }

    @Test
    @DisplayName("AC-1: per-location availability rows expose forecast fields; existing fields unchanged")
    void perLocationRowsExposeForecastFields() throws Exception {
        UUID productId = UUID.randomUUID();
        seedOnHand(productId, SITE, new BigDecimal("100"));
        seedApprovedPo(productId, SITE, "25", LocalDate.parse("2026-07-30"));
        seedPendingReservation(productId, 10, 0, null); // remainder 10, site-agnostic

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/availability/{productId}", productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                // Existing contract unchanged.
                .andExpect(jsonPath("$[0].locationId").value(SITE.toString()))
                .andExpect(jsonPath("$[0].onHandQuantity").value(100))
                .andExpect(jsonPath("$[0].availableToPromiseQuantity").value(100))
                // Additive forecast fields.
                .andExpect(jsonPath("$[0].incomingQty").value(25))
                .andExpect(jsonPath("$[0].outgoingQty").value(10))
                .andExpect(jsonPath("$[0].projectedAvailable").value(115));
    }

    @Test
    @DisplayName("AC-2: horizon bounds the forecast; undated documents drop out of bounded results")
    void horizonBoundsForecast() throws Exception {
        UUID productId = UUID.randomUUID();
        seedOnHand(productId, SITE, new BigDecimal("50"));
        seedApprovedPo(productId, SITE, "20", LocalDate.parse("2026-07-25")); // inside horizon
        seedApprovedPo(productId, SITE, "40", LocalDate.parse("2026-12-01")); // beyond horizon
        seedApprovedPo(productId, SITE, "5", null); // undated
        seedPendingReservation(productId, 8, 0, Instant.parse("2026-07-24T00:00:00Z")); // inside
        seedPendingReservation(productId, 3, 0, null); // undated

        // Unbounded: incoming 65, outgoing 11.
        mockMvc.perform(withGatewayAuth(get("/v1/inventory/availability/{productId}", productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].incomingQty").value(65))
                .andExpect(jsonPath("$[0].outgoingQty").value(11))
                .andExpect(jsonPath("$[0].projectedAvailable").value(104));

        // Bounded at 2026-08-01: incoming 20, outgoing 8; undated documents excluded.
        mockMvc.perform(withGatewayAuth(get("/v1/inventory/availability/{productId}", productId)
                        .param("horizon", "2026-08-01T00:00:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].incomingQty").value(20))
                .andExpect(jsonPath("$[0].outgoingQty").value(8))
                .andExpect(jsonPath("$[0].projectedAvailable").value(62));
    }

    @Test
    @DisplayName("AC-3: by-sku availability views expose forecast fields with unchanged ATP semantics")
    void bySkuViewExposesForecastFields() throws Exception {
        UUID productId = UUID.randomUUID();
        seedOnHand(productId, SITE, new BigDecimal("30"));
        seedApprovedPo(productId, SITE, "12", null);

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/availability/by-sku")
                        .param("productSku", productId.toString())
                        .param("locationId", SITE.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onHandQuantity").value(30))
                .andExpect(jsonPath("$.allocatedQuantity").value(0))
                .andExpect(jsonPath("$.availableToPromiseQuantity").value(30))
                .andExpect(jsonPath("$.incomingQty").value(12))
                .andExpect(jsonPath("$.outgoingQty").value(0))
                .andExpect(jsonPath("$.projectedAvailable").value(42));

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/availability")
                        .param("sku", productId.toString())
                        .param("locationId", SITE.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].incomingQty").value(12))
                .andExpect(jsonPath("$[0].projectedAvailable").value(42));
    }

    private void seedOnHand(UUID productId, UUID locationId, BigDecimal quantity) {
        ledgerPostingService.post(InventoryLedgerEntry.builder()
                .stockItemId(productId.toString())
                .locationId(locationId)
                .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                .changeInQuantity(quantity)
                .quantityAfter(quantity)
                .transactionUserId("forecast-contract-seed")
                .build());
    }

    /**
     * States what an approved order says, as its fact would.
     *
     * <p>pos-inventory has no purchase-order table any more (CAP-320 #1334); the availability
     * endpoint reads incoming supply from the projection, so that is what the fixture seeds.
     */
    private void seedApprovedPo(UUID skuId, UUID siteId, String openQuantity, LocalDate expectedDeliveryDate) {
        BigDecimal open = new BigDecimal(openQuantity);
        projection.project(
                UUID.randomUUID(),
                "APPROVED",
                siteId,
                expectedDeliveryDate,
                java.util.List.of(new com.positivity.inventory.internal.service.PurchaseOrderProjectionTestSupport.Line(
                        skuId, open.max(BigDecimal.ONE).toPlainString(), openQuantity)));
    }

    private void seedPendingReservation(UUID stockItemId, int required, int allocated, Instant dueDateTime) {
        reservationRepository.save(ReservationEntity.builder()
                .workorderLineId(UUID.randomUUID())
                .stockItemId(stockItemId)
                .requiredQuantity(BigDecimal.valueOf(required))
                .allocatedQuantity(BigDecimal.valueOf(allocated))
                .status(ReservationStatus.PENDING)
                .dueDateTime(dueDateTime)
                .build());
    }
}
