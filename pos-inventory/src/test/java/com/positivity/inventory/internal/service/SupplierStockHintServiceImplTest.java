package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.config.SupplierStockHintProperties;
import com.positivity.inventory.internal.dto.supplierhint.SupplierStockHintView;
import com.positivity.inventory.internal.entity.SupplierStockHint;
import com.positivity.inventory.internal.entity.SupplierStockSnapshotReceipt;
import com.positivity.inventory.internal.enums.SupplierHintAsOfSource;
import com.positivity.inventory.internal.enums.SupplierHintAvailability;
import com.positivity.inventory.internal.enums.SupplierHintIdentityKind;
import com.positivity.inventory.internal.enums.SupplierHintResolutionStatus;
import com.positivity.inventory.internal.repository.SupplierStockHintRepository;
import com.positivity.inventory.internal.repository.SupplierStockSnapshotReceiptRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Read contract of the supplier availability hints (CAP-322, #1312): the staleness ceiling, the
 * three-way quantity distinction surviving into the API, per-vendor rows, and partial snapshots
 * being visible as partial rather than silently trusted.
 */
class SupplierStockHintServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");
    private static final Clock TEST_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID PRODUCT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000601");
    private static final UUID VENDOR_A = UUID.fromString("018f0000-0000-7000-8000-000000000602");
    private static final UUID VENDOR_B = UUID.fromString("018f0000-0000-7000-8000-000000000603");
    private static final UUID SNAPSHOT_A = UUID.fromString("018f0000-0000-7000-8000-000000000604");
    private static final UUID SNAPSHOT_B = UUID.fromString("018f0000-0000-7000-8000-000000000605");

    private final SupplierStockHintRepository hints = mock(SupplierStockHintRepository.class);
    private final SupplierStockSnapshotReceiptRepository receipts = mock(SupplierStockSnapshotReceiptRepository.class);
    private final SupplierStockHintProperties properties = new SupplierStockHintProperties();

    private SupplierStockHintServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SupplierStockHintServiceImpl(hints, receipts, properties, TEST_CLOCK);
    }

    private static SupplierStockHint hint(UUID vendorId, UUID snapshotId, Integer quantity, Instant vendorAsOf) {
        return SupplierStockHint.builder()
                .hintId(UUID.randomUUID())
                .vendorProfileId(vendorId)
                .identityKind(SupplierHintIdentityKind.EAN)
                .identityValue("4012345678901")
                .articleEan("4012345678901")
                .supplierArticleCode("V-1")
                .buyersArticleId("B-1")
                .description("Tyre 205/55R16")
                .availableQuantity(quantity)
                .resolutionStatus(SupplierHintResolutionStatus.RESOLVED)
                .resolvedProductId(PRODUCT_ID)
                .sourceSnapshotId(snapshotId)
                .sourceChunkSequence(1)
                .supplierRef("ediwheel-b21")
                .snapshotAsOf(vendorAsOf)
                .asOfSource(SupplierHintAsOfSource.VENDOR)
                .fetchedAt(vendorAsOf.plusSeconds(300))
                .firstSeenAt(vendorAsOf)
                .build();
    }

    private static SupplierStockSnapshotReceipt receipt(UUID snapshotId, int applied, int count) {
        return SupplierStockSnapshotReceipt.builder()
                .snapshotId(snapshotId)
                .vendorProfileId(VENDOR_A)
                .chunkCount(count)
                .chunksApplied(applied)
                .linesReported(10)
                .linesApplied(applied * 5)
                .asOfSource(SupplierHintAsOfSource.VENDOR)
                .fetchedAt(NOW)
                .complete(applied >= count)
                .firstChunkAt(NOW)
                .lastChunkAt(NOW)
                .build();
    }

    @Test
    @DisplayName("a fresh stated quantity is reported as the vendor stated it")
    void fresh_quantity_is_reported() {
        when(hints.findByResolvedProductIdOrderByFetchedAtDesc(PRODUCT_ID))
                .thenReturn(List.of(hint(VENDOR_A, SNAPSHOT_A, 12, NOW.minus(Duration.ofHours(2)))));
        when(receipts.findBySnapshotId(SNAPSHOT_A)).thenReturn(Optional.of(receipt(SNAPSHOT_A, 2, 2)));

        List<SupplierStockHintView> views = service.findByProduct(PRODUCT_ID);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).availability()).isEqualTo(SupplierHintAvailability.REPORTED);
        assertThat(views.get(0).quantity()).isEqualTo(12);
        assertThat(views.get(0).snapshotAsOf()).isEqualTo(NOW.minus(Duration.ofHours(2)));
        assertThat(views.get(0).asOfSource()).isEqualTo(SupplierHintAsOfSource.VENDOR);
        assertThat(views.get(0).snapshotComplete()).isTrue();
    }

    @Test
    @DisplayName("an explicit zero reads as a reported zero, not as an absence")
    void explicit_zero_is_reported() {
        when(hints.findByResolvedProductIdOrderByFetchedAtDesc(PRODUCT_ID))
                .thenReturn(List.of(hint(VENDOR_A, SNAPSHOT_A, 0, NOW.minus(Duration.ofHours(1)))));

        List<SupplierStockHintView> views = service.findByProduct(PRODUCT_ID);

        assertThat(views.get(0).availability()).isEqualTo(SupplierHintAvailability.REPORTED);
        assertThat(views.get(0).quantity()).isZero();
    }

    @Test
    @DisplayName("a vendor listing an article without a quantity does not read as zero")
    void unstated_quantity_is_its_own_state() {
        when(hints.findByResolvedProductIdOrderByFetchedAtDesc(PRODUCT_ID))
                .thenReturn(List.of(hint(VENDOR_A, SNAPSHOT_A, null, NOW.minus(Duration.ofHours(1)))));

        List<SupplierStockHintView> views = service.findByProduct(PRODUCT_ID);

        assertThat(views.get(0).availability()).isEqualTo(SupplierHintAvailability.QUANTITY_NOT_STATED);
        assertThat(views.get(0).quantity()).isNull();
    }

    @Test
    @DisplayName("past the ceiling a hint reads as unknown, never as zero")
    void stale_hint_reads_as_unknown() {
        when(hints.findByResolvedProductIdOrderByFetchedAtDesc(PRODUCT_ID))
                .thenReturn(List.of(hint(VENDOR_A, SNAPSHOT_A, 12, NOW.minus(Duration.ofHours(30)))));

        List<SupplierStockHintView> views = service.findByProduct(PRODUCT_ID);

        assertThat(views.get(0).availability()).isEqualTo(SupplierHintAvailability.STALE_UNKNOWN);
        assertThat(views.get(0).quantity()).isNull();
        // The vendor's own figure is still exposed: the caller can see how old it is, and that the
        // age is the vendor's own statement rather than our fetch clock.
        assertThat(views.get(0).snapshotAsOf()).isEqualTo(NOW.minus(Duration.ofHours(30)));
    }

    @Test
    @DisplayName("a vendor on a tighter cadence gets its own ceiling")
    void per_vendor_ceiling_overrides_the_default() {
        properties.getVendorStalenessCeiling().put(VENDOR_A.toString(), Duration.ofHours(4));
        when(hints.findByResolvedProductIdOrderByFetchedAtDesc(PRODUCT_ID))
                .thenReturn(List.of(
                        hint(VENDOR_A, SNAPSHOT_A, 12, NOW.minus(Duration.ofHours(6))),
                        hint(VENDOR_B, SNAPSHOT_B, 8, NOW.minus(Duration.ofHours(6)))));

        List<SupplierStockHintView> views = service.findByProduct(PRODUCT_ID);

        // Same age, different verdicts: vendor A is polled every few hours, vendor B daily.
        assertThat(views.get(0).availability()).isEqualTo(SupplierHintAvailability.STALE_UNKNOWN);
        assertThat(views.get(1).availability()).isEqualTo(SupplierHintAvailability.REPORTED);
        assertThat(views.get(1).quantity()).isEqualTo(8);
    }

    @Test
    @DisplayName("age is measured on our fetch clock only when the vendor stated no time, and says so")
    void fetch_clock_fallback_is_marked() {
        SupplierStockHint noVendorTime = hint(VENDOR_A, SNAPSHOT_A, 12, NOW.minus(Duration.ofHours(2)));
        noVendorTime.setSnapshotAsOf(null);
        noVendorTime.setAsOfSource(SupplierHintAsOfSource.FETCH);
        when(hints.findByResolvedProductIdOrderByFetchedAtDesc(PRODUCT_ID)).thenReturn(List.of(noVendorTime));

        List<SupplierStockHintView> views = service.findByProduct(PRODUCT_ID);

        assertThat(views.get(0).snapshotAsOf()).isNull();
        assertThat(views.get(0).asOfSource()).isEqualTo(SupplierHintAsOfSource.FETCH);
        assertThat(views.get(0).availability()).isEqualTo(SupplierHintAvailability.REPORTED);
    }

    @Test
    @DisplayName("hints from an incomplete snapshot are returned flagged, not withheld")
    void partial_snapshot_is_visible() {
        when(hints.findByResolvedProductIdOrderByFetchedAtDesc(PRODUCT_ID))
                .thenReturn(List.of(hint(VENDOR_A, SNAPSHOT_A, 12, NOW.minus(Duration.ofHours(1)))));
        when(receipts.findBySnapshotId(SNAPSHOT_A)).thenReturn(Optional.of(receipt(SNAPSHOT_A, 1, 3)));

        List<SupplierStockHintView> views = service.findByProduct(PRODUCT_ID);

        assertThat(views.get(0).snapshotComplete()).isFalse();
        assertThat(views.get(0).chunksApplied()).isEqualTo(1);
        assertThat(views.get(0).chunkCount()).isEqualTo(3);
        // What the vendor said about THIS article is unaffected by the chunk we never got.
        assertThat(views.get(0).quantity()).isEqualTo(12);
    }

    @Test
    @DisplayName("each vendor's statement stands on its own and is never aggregated")
    void vendors_are_listed_separately() {
        when(hints.findByResolvedProductIdOrderByFetchedAtDesc(PRODUCT_ID))
                .thenReturn(List.of(
                        hint(VENDOR_A, SNAPSHOT_A, 12, NOW.minus(Duration.ofHours(1))),
                        hint(VENDOR_B, SNAPSHOT_B, 8, NOW.minus(Duration.ofHours(3)))));

        List<SupplierStockHintView> views = service.findByProduct(PRODUCT_ID);

        assertThat(views).hasSize(2);
        assertThat(views).extracting(SupplierStockHintView::vendorProfileId).containsExactly(VENDOR_A, VENDOR_B);
        assertThat(views).extracting(SupplierStockHintView::quantity).containsExactly(12, 8);
    }

    @Test
    @DisplayName("hints for one vendor cost one receipt lookup, not one per hint")
    void receipt_lookups_are_deduped_per_snapshot() {
        when(hints.findByArticleEanOrderByFetchedAtDesc("4012345678901"))
                .thenReturn(List.of(
                        hint(VENDOR_A, SNAPSHOT_A, 12, NOW.minus(Duration.ofHours(1))),
                        hint(VENDOR_A, SNAPSHOT_A, 4, NOW.minus(Duration.ofHours(1)))));

        service.findByCode(SupplierHintIdentityKind.EAN, "4012345678901");

        verify(receipts, times(1)).findBySnapshotId(SNAPSHOT_A);
    }

    @Test
    @DisplayName("an unresolved article is still reachable by the code the vendor stated")
    void code_lookup_matches_the_stated_identifier() {
        SupplierStockHint unresolved = hint(VENDOR_A, SNAPSHOT_A, 5, NOW.minus(Duration.ofHours(1)));
        unresolved.setResolvedProductId(null);
        unresolved.setResolutionStatus(SupplierHintResolutionStatus.UNRESOLVED);
        when(hints.findByBuyersArticleIdOrderByFetchedAtDesc("B-1")).thenReturn(List.of(unresolved));

        List<SupplierStockHintView> views = service.findByCode(SupplierHintIdentityKind.BUYER_ARTICLE, "  B-1  ");

        assertThat(views).hasSize(1);
        assertThat(views.get(0).resolvedProductId()).isNull();
        assertThat(views.get(0).quantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("a blank code matches nothing without going to the database")
    void blank_code_short_circuits() {
        assertThat(service.findByCode(SupplierHintIdentityKind.EAN, "   ")).isEmpty();
        verify(hints, times(0)).findByArticleEanOrderByFetchedAtDesc("");
    }

    @Test
    @DisplayName("a hint whose snapshot receipt is missing reads as incomplete rather than complete")
    void missing_receipt_is_not_treated_as_complete() {
        when(hints.findByResolvedProductIdOrderByFetchedAtDesc(PRODUCT_ID))
                .thenReturn(List.of(hint(VENDOR_A, SNAPSHOT_A, 12, NOW.minus(Duration.ofHours(1)))));
        when(receipts.findBySnapshotId(SNAPSHOT_A)).thenReturn(Optional.empty());

        List<SupplierStockHintView> views = service.findByProduct(PRODUCT_ID);

        assertThat(views.get(0).snapshotComplete()).isFalse();
    }
}
