package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.entity.DistributorNormalizedInventory;
import com.positivity.inventory.internal.entity.NormalizedAvailability;
import com.positivity.inventory.internal.enums.SuggestedVendorType;
import com.positivity.inventory.internal.repository.DistributorNormalizedInventoryRepository;
import com.positivity.inventory.internal.repository.NormalizedAvailabilityRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Determinism vectors for the F4 vendor ranking (odoo-parity F4, issue #1044):
 * sufficiency (availability ≥ need) → shortest MAX-day lead time → lowest price →
 * vendor UUID ascending, across BOTH normalized feeds.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Vendor selection determinism (F4)")
class VendorSelectionServiceTest {

    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID VENDOR_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID VENDOR_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Mock
    private NormalizedAvailabilityRepository normalizedAvailabilityRepository;

    @Mock
    private DistributorNormalizedInventoryRepository distributorNormalizedInventoryRepository;

    @InjectMocks
    private VendorSelectionService vendorSelectionService;

    private static NormalizedAvailability manufacturerRow(
            UUID manufacturerId, int availableQty, Integer leadMax, BigDecimal price, Instant asOf) {
        return NormalizedAvailability.builder()
                .id(UUID.randomUUID())
                .productId(PRODUCT_ID)
                .manufacturerId(manufacturerId)
                .manufacturerPartNumber("MPN-1")
                .availableQty(availableQty)
                .uom("EA")
                .unitPriceAmount(price)
                .unitPriceCurrency(price != null ? "USD" : null)
                .leadTimeDaysMax(leadMax)
                .packSize(1)
                .asOf(asOf)
                .receivedAt(asOf)
                .schemaVersion(1)
                .build();
    }

    private static DistributorNormalizedInventory distributorRow(
            String distributorId, int quantityAvailable, Integer leadMax) {
        return DistributorNormalizedInventory.builder()
                .id(UUID.randomUUID())
                .productId(PRODUCT_ID)
                .distributorId(distributorId)
                .distributorSku("DSKU-1")
                .quantityAvailable(quantityAvailable)
                .leadTimeDaysMax(leadMax)
                .normalizationPolicyVersion("v1")
                .updatedAt(Instant.parse("2026-07-01T00:00:00Z"))
                .build();
    }

    private void feeds(List<NormalizedAvailability> manufacturers, List<DistributorNormalizedInventory> distributors) {
        when(normalizedAvailabilityRepository.findByProductIdOrderByAsOfDesc(PRODUCT_ID))
                .thenReturn(manufacturers);
        when(distributorNormalizedInventoryRepository.findByProductId(PRODUCT_ID))
                .thenReturn(distributors);
    }

    @Test
    @DisplayName("sufficient availability beats a faster but insufficient vendor")
    void sufficiencyBeatsLeadTime() {
        Instant asOf = Instant.parse("2026-07-01T00:00:00Z");
        feeds(
                List.of(
                        manufacturerRow(VENDOR_A, 40, 5, new BigDecimal("4.99"), asOf),
                        manufacturerRow(VENDOR_B, 8, 1, new BigDecimal("3.99"), asOf)),
                List.of());

        var selection = vendorSelectionService.selectVendor(PRODUCT_ID, 12);

        assertThat(selection.chosen()).isNotNull();
        assertThat(selection.chosen().vendorRefId()).isEqualTo(VENDOR_A);
        assertThat(selection.selectionReason()).contains("MANUFACTURER " + VENDOR_A + ": avail 40>=12, lead 5d");
        assertThat(selection.selectionReason()).contains("beat MANUFACTURER " + VENDOR_B + ": avail 8<12, lead 1d");
    }

    @Test
    @DisplayName("among sufficient vendors, the shortest MAX-day lead time wins (cross-source)")
    void leadTimeBreaksSufficiencyTie() {
        Instant asOf = Instant.parse("2026-07-01T00:00:00Z");
        feeds(
                List.of(manufacturerRow(VENDOR_A, 40, 7, new BigDecimal("2.99"), asOf)),
                List.of(distributorRow(VENDOR_B.toString(), 40, 3)));

        var selection = vendorSelectionService.selectVendor(PRODUCT_ID, 12);

        assertThat(selection.chosen().sourceType()).isEqualTo(SuggestedVendorType.DISTRIBUTOR);
        assertThat(selection.chosen().vendorRefId()).isEqualTo(VENDOR_B);
        assertThat(selection.selectionReason()).startsWith("DISTRIBUTOR " + VENDOR_B);
    }

    @Test
    @DisplayName("equal sufficiency and lead time: a priced vendor beats a priceless one, cheaper beats dearer")
    void priceBreaksLeadTimeTie() {
        Instant asOf = Instant.parse("2026-07-01T00:00:00Z");
        // Distributor rows never carry a price, so the priced manufacturer wins at equal lead.
        feeds(
                List.of(manufacturerRow(VENDOR_B, 40, 5, new BigDecimal("4.99"), asOf)),
                List.of(distributorRow(VENDOR_A.toString(), 40, 5)));

        var selection = vendorSelectionService.selectVendor(PRODUCT_ID, 12);

        assertThat(selection.chosen().vendorRefId()).isEqualTo(VENDOR_B);
        assertThat(selection.chosen().unitCostMinor()).isEqualTo(499L);

        // And between two priced vendors the cheaper wins.
        feeds(
                List.of(
                        manufacturerRow(VENDOR_A, 40, 5, new BigDecimal("5.49"), asOf),
                        manufacturerRow(VENDOR_B, 40, 5, new BigDecimal("4.99"), asOf)),
                List.of());
        assertThat(vendorSelectionService.selectVendor(PRODUCT_ID, 12).chosen().vendorRefId())
                .isEqualTo(VENDOR_B);
    }

    @Test
    @DisplayName("cross-source full tie falls back to vendor UUID ascending — stable and deterministic")
    void uuidBreaksFullTie() {
        Instant asOf = Instant.parse("2026-07-01T00:00:00Z");
        // Manufacturer with no price vs distributor (never priced): same avail, same lead,
        // both priceless → lowest UUID wins regardless of source.
        feeds(
                List.of(manufacturerRow(VENDOR_B, 40, 5, null, asOf)),
                List.of(distributorRow(VENDOR_A.toString(), 40, 5)));

        var first = vendorSelectionService.selectVendor(PRODUCT_ID, 12);
        var second = vendorSelectionService.selectVendor(PRODUCT_ID, 12);

        assertThat(first.chosen().vendorRefId()).isEqualTo(VENDOR_A);
        assertThat(first.chosen().sourceType()).isEqualTo(SuggestedVendorType.DISTRIBUTOR);
        assertThat(second.chosen().vendorRefId()).isEqualTo(VENDOR_A); // repeatable
    }

    @Test
    @DisplayName("no vendor covers the need: best lead time still chosen, reason records the shortfall")
    void shortfallStillSelectsBestLeadTime() {
        Instant asOf = Instant.parse("2026-07-01T00:00:00Z");
        feeds(
                List.of(
                        manufacturerRow(VENDOR_A, 8, 4, new BigDecimal("4.99"), asOf),
                        manufacturerRow(VENDOR_B, 10, 9, new BigDecimal("3.99"), asOf)),
                List.of());

        var selection = vendorSelectionService.selectVendor(PRODUCT_ID, 50);

        assertThat(selection.chosen().vendorRefId()).isEqualTo(VENDOR_A); // shortest lead among insufficient
        assertThat(selection.selectionReason()).contains("avail 8<50");
        assertThat(selection.selectionReason()).contains("no candidate covers need 50");
        assertThat(selection.selectionReason()).contains("best lead time chosen despite shortfall");
    }

    @Test
    @DisplayName("only the latest snapshot per manufacturer participates")
    void latestSnapshotPerManufacturerWins() {
        Instant newer = Instant.parse("2026-07-02T00:00:00Z");
        Instant older = Instant.parse("2026-07-01T00:00:00Z");
        // Repository returns newest-first; the stale row must be ignored.
        feeds(
                List.of(
                        manufacturerRow(VENDOR_A, 40, 5, new BigDecimal("4.99"), newer),
                        manufacturerRow(VENDOR_A, 0, 30, new BigDecimal("9.99"), older)),
                List.of());

        var selection = vendorSelectionService.selectVendor(PRODUCT_ID, 12);

        assertThat(selection.chosen().availableQty()).isEqualTo(40);
        assertThat(selection.chosen().leadTimeDays()).isEqualTo(5);
        assertThat(selection.chosen().unitCostMinor()).isEqualTo(499L);
    }

    @Test
    @DisplayName("distributor rows with non-UUID ids are excluded — they cannot be PO vendors")
    void nonUuidDistributorExcluded() {
        Instant asOf = Instant.parse("2026-07-01T00:00:00Z");
        feeds(
                List.of(manufacturerRow(VENDOR_B, 8, 9, new BigDecimal("4.99"), asOf)),
                List.of(distributorRow("ACME-DIST", 500, 1)));

        var selection = vendorSelectionService.selectVendor(PRODUCT_ID, 12);

        // The non-UUID distributor would have won on every criterion — it must be absent.
        assertThat(selection.chosen().vendorRefId()).isEqualTo(VENDOR_B);
    }

    @Test
    @DisplayName("no feed rows at all: no vendor, explanatory reason")
    void noFeedRowsYieldsNoVendor() {
        feeds(List.of(), List.of());

        var selection = vendorSelectionService.selectVendor(PRODUCT_ID, 12);

        assertThat(selection.chosen()).isNull();
        assertThat(selection.selectionReason()).contains("no normalized manufacturer or distributor feed rows");
    }

    @Test
    @DisplayName("non-product SKU (null product id): no vendor, explanatory reason")
    void nullProductIdYieldsNoVendor() {
        var selection = vendorSelectionService.selectVendor(null, 12);

        assertThat(selection.chosen()).isNull();
        assertThat(selection.selectionReason()).contains("not a catalog product UUID");
    }
}
