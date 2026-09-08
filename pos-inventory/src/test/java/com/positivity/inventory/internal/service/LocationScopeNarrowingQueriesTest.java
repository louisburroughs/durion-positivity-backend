package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.internal.entity.BackorderRecord;
import com.positivity.inventory.internal.entity.CycleCountPlan;
import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.entity.LocationRefEntity;
import com.positivity.inventory.internal.entity.PurchaseSuggestion;
import com.positivity.inventory.internal.entity.ReplenishmentPolicy;
import com.positivity.inventory.internal.enums.BackorderStatus;
import com.positivity.inventory.internal.enums.CycleCountPlanStatus;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.PurchaseSuggestionStatus;
import com.positivity.inventory.internal.repository.BackorderRecordRepository;
import com.positivity.inventory.internal.repository.CycleCountPlanRepository;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.LocationRefRepository;
import com.positivity.inventory.internal.repository.PurchaseSuggestionRepository;
import com.positivity.inventory.internal.repository.ReplenishmentPolicyRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * The narrowed queries behind the #1872 list endpoints, against a real database: a row is
 * admitted when its location is a reachable site <em>or</em> a storage location replicated under
 * one, and never when it sits at a site outside the reach. Pins both the JPQL {@code IN} +
 * subquery shape and the Criteria {@link LocationScopeService#withinLocations} equivalent.
 *
 * <p>Boots the module context like the other pos-inventory persistence tests (no
 * {@code @DataJpaTest} slice here) and rolls each test back.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("pos-inventory narrowed queries admit reachable sites and their bins only")
class LocationScopeNarrowingQueriesTest {

    private static final UUID SITE_A = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");
    private static final UUID SITE_B = UUID.fromString("018f0000-0000-7000-8000-0000000000b1");
    private static final UUID BIN_A = UUID.fromString("018f0000-0000-7000-8000-0000000000a2");
    private static final Set<UUID> REACH_A = Set.of(SITE_A);
    private static final String SKU = "SKU-NARROW";

    @Autowired
    private LocationRefRepository locationRefRepository;

    @Autowired
    private ExtStorageLocationReplicaRepository extStorageLocationReplicaRepository;

    @Autowired
    private BackorderRecordRepository backorderRecordRepository;

    @Autowired
    private CycleCountPlanRepository cycleCountPlanRepository;

    @Autowired
    private PurchaseSuggestionRepository purchaseSuggestionRepository;

    @Autowired
    private ReplenishmentPolicyRepository replenishmentPolicyRepository;

    @Autowired
    private InventoryStockSummaryRepository inventoryStockSummaryRepository;

    @Autowired
    private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    @BeforeEach
    void seedTopology() {
        locationRefRepository.save(LocationRefEntity.builder()
                .locationId(SITE_A)
                .name("Site A")
                .status("ACTIVE")
                .active(true)
                .build());
        locationRefRepository.save(LocationRefEntity.builder()
                .locationId(SITE_B)
                .name("Site B")
                .status("ACTIVE")
                .active(true)
                .build());
        extStorageLocationReplicaRepository.save(ExtStorageLocationReplica.builder()
                .storageLocationId(BIN_A)
                .siteId(SITE_A)
                .name("Bin A")
                .type("BIN")
                .status("ACTIVE")
                .aggregateVersion(1L)
                .build());
    }

    @Test
    @DisplayName("Specification: backorders at the site and in its bin, not at the other site")
    void backorderSpecification() {
        for (UUID location : List.of(SITE_A, BIN_A, SITE_B)) {
            backorderRecordRepository.save(BackorderRecord.builder()
                    .sku(SKU)
                    .locationId(location)
                    .quantityShort(BigDecimal.ONE)
                    .status(BackorderStatus.OPEN)
                    .createdBy("test")
                    .build());
        }

        List<BackorderRecord> found = backorderRecordRepository.findAll(
                LocationScopeService.withinLocations("locationId", REACH_A), Sort.by("createdAt"));

        assertThat(found).extracting(BackorderRecord::getLocationId).containsExactlyInAnyOrder(SITE_A, BIN_A);
        assertThat(backorderRecordRepository.count(LocationScopeService.withinLocations("locationId", REACH_A)))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("JPQL: cycle count plans within reach, with the status filter still applied")
    void cycleCountPlansWithinLocations() {
        for (UUID location : List.of(SITE_A, BIN_A, SITE_B)) {
            cycleCountPlanRepository.save(CycleCountPlan.builder()
                    .locationId(location)
                    .zoneIds(List.of())
                    .planName("Plan")
                    .scheduledDate(LocalDate.of(2026, 9, 15))
                    .status(CycleCountPlanStatus.PLANNED)
                    .createdBy("test")
                    .build());
        }

        List<CycleCountPlan> found = cycleCountPlanRepository
                .findByOptionalFiltersWithinLocations(REACH_A, null, Pageable.unpaged())
                .getContent();
        List<CycleCountPlan> none = cycleCountPlanRepository
                .findByOptionalFiltersWithinLocations(REACH_A, CycleCountPlanStatus.CANCELLED, Pageable.unpaged())
                .getContent();

        assertThat(found).extracting(CycleCountPlan::getLocationId).containsExactlyInAnyOrder(SITE_A, BIN_A);
        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("JPQL: purchase suggestions and replenishment policies within reach")
    void purchaseSuggestionsAndPoliciesWithinLocations() {
        for (UUID location : List.of(SITE_A, BIN_A, SITE_B)) {
            purchaseSuggestionRepository.save(PurchaseSuggestion.builder()
                    .policyId(UUID.randomUUID())
                    .itemSKU(SKU)
                    .locationId(location)
                    .suggestedQuantity(3)
                    .selectionReason("test")
                    .status(PurchaseSuggestionStatus.SUGGESTED)
                    .build());
            replenishmentPolicyRepository.save(ReplenishmentPolicy.builder()
                    .locationId(location)
                    .itemSKU(SKU + "-" + location)
                    .minimumQuantity(1)
                    .maximumQuantity(5)
                    .build());
        }

        assertThat(purchaseSuggestionRepository.findWithinLocations(REACH_A))
                .extracting(PurchaseSuggestion::getLocationId)
                .containsExactlyInAnyOrder(SITE_A, BIN_A);
        assertThat(replenishmentPolicyRepository.findWithinLocations(REACH_A))
                .extracting(ReplenishmentPolicy::getLocationId)
                .containsExactlyInAnyOrder(SITE_A, BIN_A);
    }

    @Test
    @DisplayName("aggregates: on-hand summed over the site and its bin only, current and as-of")
    void onHandAggregatesWithinLocations() {
        Instant now = Instant.parse("2026-09-07T00:00:00Z");
        for (UUID location : List.of(SITE_A, BIN_A, SITE_B)) {
            inventoryStockSummaryRepository.save(InventoryStockSummary.builder()
                    .stockItemId(SKU)
                    .locationId(location)
                    .onHand(new BigDecimal("2"))
                    .build());
            inventoryLedgerEntryRepository.save(InventoryLedgerEntry.builder()
                    .stockItemId(SKU)
                    .locationId(location)
                    .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                    .changeInQuantity(new BigDecimal("2"))
                    .quantityAfter(new BigDecimal("2"))
                    .transactionUserId("test")
                    .timestamp(now)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        Set<InventoryLedgerEventType> onHandTypes = InventoryLedgerEventType.onHandAffectingTypes();
        // The entry's timestamp is @CreatedDate, so auditing stamps the wall clock on save; an
        // as-of far in the future keeps the assertion deterministic without depending on it.
        Instant asOf = Instant.parse("2999-01-01T00:00:00Z");

        assertThat(inventoryStockSummaryRepository.sumOnHandForSkuWithinLocations(SKU, REACH_A))
                .isEqualByComparingTo("4");
        assertThat(inventoryStockSummaryRepository.sumOnHandBySkuWithinLocations(REACH_A))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getStockItemId()).isEqualTo(SKU);
                    assertThat(row.getOnHand()).isEqualByComparingTo("4");
                });
        assertThat(inventoryLedgerEntryRepository.calculateOnHandForStockItemWithinLocationsAsOf(
                        SKU, REACH_A, onHandTypes, asOf))
                .isEqualByComparingTo("4");
        assertThat(inventoryLedgerEntryRepository.sumOnHandBySkuWithinLocationsAsOf(REACH_A, onHandTypes, asOf))
                .singleElement()
                .satisfies(row -> assertThat(row.getOnHandQuantity()).isEqualByComparingTo("4"));
    }
}
