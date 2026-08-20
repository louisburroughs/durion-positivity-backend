package com.positivity.inventory.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.LocationRefEntity;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.LocationRefRepository;
import com.positivity.inventory.internal.service.LedgerPostingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Cross-site guard on immediate stock movements (odoo-parity C2 / spec C7, issue #1036):
 * {@code POST /v1/inventory/stock-movements} TRANSFER is retained for intra-site bin moves
 * only — from/to resolving to DIFFERENT sites is a deterministic 422
 * ({@code CROSS_SITE_TRANSFER_REQUIRES_ORDER}); same-site bins and ids not resolvable to any
 * site (no roster/replica row) keep the pre-C1 behavior.
 */
@DisplayName("Cross-Site Transfer Guard Behavior")
class CrossSiteTransferGuardIT extends BaseContractIntegrationTest {

    private static final UUID SITE_A = UUID.fromString("01970004-0001-7000-8000-000000000001");
    private static final UUID SITE_B = UUID.fromString("01970004-0001-7000-8000-000000000002");
    private static final UUID BIN_A1 = UUID.fromString("01970004-0002-7000-8000-000000000001");
    private static final UUID BIN_A2 = UUID.fromString("01970004-0002-7000-8000-000000000002");
    private static final UUID BIN_B1 = UUID.fromString("01970004-0002-7000-8000-000000000003");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LocationRefRepository locationRefRepository;

    @Autowired
    private ExtStorageLocationReplicaRepository storageLocationRepository;

    @Autowired
    private InventoryLedgerEntryRepository ledgerRepository;

    @Autowired
    private InventoryStockSummaryRepository summaryRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @BeforeEach
    void setUp() {
        locationRefRepository.deleteAll();
        seedSite(SITE_A, "Guard Site A");
        seedSite(SITE_B, "Guard Site B");
        seedBin(BIN_A1, SITE_A, "A-01-01");
        seedBin(BIN_A2, SITE_A, "A-01-02");
        seedBin(BIN_B1, SITE_B, "B-01-01");
    }

    @Test
    @DisplayName("TRANSFER between bins of different sites returns 422 CROSS_SITE_TRANSFER_REQUIRES_ORDER")
    void transfer_binsAcrossSites_returns422() throws Exception {
        String sku = uniqueSku();
        seedOnHand(sku, BIN_A1, new BigDecimal("10"));

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/stock-movements"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(sku, BIN_A1, BIN_B1, 5)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CROSS_SITE_TRANSFER_REQUIRES_ORDER"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("transfer order")));

        // Nothing moved.
        assertThat(ledgerRepository.findByStockItemIdOrderByTimestampDesc(sku))
                .noneMatch(entry -> entry.getEventType() == InventoryLedgerEventType.TRANSFER_OUT
                        || entry.getEventType() == InventoryLedgerEventType.TRANSFER_IN);
    }

    @Test
    @DisplayName("TRANSFER between site keys of different sites returns 422")
    void transfer_siteToSite_returns422() throws Exception {
        String sku = uniqueSku();
        seedOnHand(sku, SITE_A, new BigDecimal("10"));

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/stock-movements"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(sku, SITE_A, SITE_B, 5)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CROSS_SITE_TRANSFER_REQUIRES_ORDER"));
    }

    @Test
    @DisplayName("TRANSFER between bins of the same site still posts the paired OUT/IN with zero net in-transit")
    void transfer_binsSameSite_allowed() throws Exception {
        String sku = uniqueSku();
        seedOnHand(sku, BIN_A1, new BigDecimal("10"));

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/stock-movements"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(sku, BIN_A1, BIN_A2, 4)))
                .andExpect(status().isCreated());

        assertThat(onHand(sku, BIN_A1)).isEqualByComparingTo("6");
        assertThat(onHand(sku, BIN_A2)).isEqualByComparingTo("4");
        // The paired OUT/IN posts in one transaction: in-transit cancels to zero everywhere.
        assertThat(summaryRepository.findByStockItemId(sku))
                .allMatch(row -> row.getInTransitQty().signum() == 0);
    }

    @Test
    @DisplayName("TRANSFER between ids not resolvable to any site keeps the pre-C1 behavior")
    void transfer_unresolvableIds_allowed() throws Exception {
        String sku = uniqueSku();
        UUID freeFrom = UUID.randomUUID();
        UUID freeTo = UUID.randomUUID();
        seedOnHand(sku, freeFrom, new BigDecimal("10"));

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/stock-movements"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(sku, freeFrom, freeTo, 3)))
                .andExpect(status().isCreated());

        assertThat(onHand(sku, freeFrom)).isEqualByComparingTo("7");
        assertThat(onHand(sku, freeTo)).isEqualByComparingTo("3");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String uniqueSku() {
        return "SKU-GUARD-" + UUID.randomUUID();
    }

    private String transferBody(String sku, UUID from, UUID to, int quantity) {
        return "{\"productSku\":\"" + sku + "\",\"fromLocationId\":\"" + from + "\",\"toLocationId\":\"" + to
                + "\",\"movementType\":\"TRANSFER\",\"quantity\":" + quantity + ",\"unitOfMeasure\":\"EACH\"}";
    }

    private void seedSite(UUID siteId, String name) {
        locationRefRepository.save(LocationRefEntity.builder()
                .locationId(siteId)
                .name(name)
                .status("ACTIVE")
                .active(true)
                .build());
    }

    private void seedBin(UUID binId, UUID siteId, String name) {
        storageLocationRepository.save(ExtStorageLocationReplica.builder()
                .storageLocationId(binId)
                .siteId(siteId)
                .name(name)
                .type("BIN")
                .status("ACTIVE")
                .aggregateVersion(1L)
                .build());
    }

    private void seedOnHand(String sku, UUID locationId, BigDecimal quantity) {
        ledgerPostingService.post(InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .locationId(locationId)
                .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                .changeInQuantity(quantity)
                .quantityAfter(quantity)
                .transactionUserId("contract-test-user")
                .timestamp(Instant.now())
                .build());
    }

    private BigDecimal onHand(String sku, UUID locationId) {
        return summaryRepository
                .findByStockItemIdAndLocationId(sku, locationId)
                .map(row -> row.getOnHand())
                .orElse(BigDecimal.ZERO);
    }
}
