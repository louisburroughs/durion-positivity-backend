package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.config.SupplierStockHintProperties;
import com.positivity.inventory.internal.entity.ExtProductCodeReplica;
import com.positivity.inventory.internal.entity.SupplierStockHint;
import com.positivity.inventory.internal.enums.SupplierHintAsOfSource;
import com.positivity.inventory.internal.enums.SupplierHintIdentityKind;
import com.positivity.inventory.internal.enums.SupplierHintResolutionStatus;
import com.positivity.inventory.internal.repository.ExtProductCodeReplicaRepository;
import com.positivity.inventory.internal.repository.SupplierStockHintRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

/**
 * Article resolution against pos-catalog's exact-match EAN lookup (CAP-322, #1312).
 *
 * <p>Resolution reads the local ext_product_code replica, never pos-catalog directly (ADR-0044
 * R1/R3). The contract worth holding: an article that cannot be resolved is retained and visible in
 * every case, and the reason is recorded rather than flattened — the replica does not carry the
 * code, it carries it twice, the vendor stated no EAN, or the replica cannot answer at all.
 */
class SupplierStockHintResolverTest {

    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");
    private static final Clock TEST_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID PRODUCT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000701");
    private static final UUID VENDOR_ID = UUID.fromString("018f0000-0000-7000-8000-000000000702");

    private final SupplierStockHintRepository hints = mock(SupplierStockHintRepository.class);
    private final ExtProductCodeReplicaRepository productCodes = mock(ExtProductCodeReplicaRepository.class);
    private final SupplierStockHintProperties properties = new SupplierStockHintProperties();

    private SupplierStockHintResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new SupplierStockHintResolver(hints, productCodes, properties, TEST_CLOCK);
    }

    private static SupplierStockHint pending(String ean) {
        return SupplierStockHint.builder()
                .hintId(UUID.fromString("018f0000-0000-7000-8000-000000000703"))
                .vendorProfileId(VENDOR_ID)
                .identityKind(ean == null ? SupplierHintIdentityKind.BUYER_ARTICLE : SupplierHintIdentityKind.EAN)
                .identityValue(ean == null ? "B-1" : ean)
                .articleEan(ean)
                .buyersArticleId("B-1")
                .availableQuantity(3)
                .resolutionStatus(SupplierHintResolutionStatus.PENDING)
                .sourceSnapshotId(UUID.fromString("018f0000-0000-7000-8000-000000000704"))
                .sourceChunkSequence(1)
                .asOfSource(SupplierHintAsOfSource.VENDOR)
                .snapshotAsOf(NOW.minusSeconds(3600))
                .fetchedAt(NOW.minusSeconds(3000))
                .firstSeenAt(NOW.minusSeconds(3000))
                .build();
    }

    private static ExtProductCodeReplica productCode(UUID productId, String ean) {
        return ExtProductCodeReplica.builder()
                .productId(productId)
                .codeType("EAN")
                .code(ean)
                .aggregateVersion(1L)
                .updatedAt(NOW.minusSeconds(600))
                .build();
    }

    /** A seeded replica; the empty case is its own test, since it defers rather than resolves. */
    private void replicaHolds(String ean, ExtProductCodeReplica... rows) {
        when(productCodes.count()).thenReturn(1L);
        when(productCodes.findByCodeTypeAndCode("EAN", ean)).thenReturn(List.of(rows));
    }

    private void backlog(SupplierStockHint... pending) {
        when(hints.findByResolutionStatusOrderByFetchedAtAsc(
                        eq(SupplierHintResolutionStatus.PENDING), any(Limit.class)))
                .thenReturn(List.of(pending));
    }

    @Test
    @DisplayName("an EAN catalog carries resolves the hint to that product")
    void matched_ean_resolves() {
        SupplierStockHint hint = pending("4012345678901");
        backlog(hint);
        replicaHolds("4012345678901", productCode(PRODUCT_ID, "4012345678901"));

        SupplierStockHintResolver.ResolutionPassResult result = resolver.runResolutionPass();

        assertThat(result.resolved()).isEqualTo(1);
        assertThat(hint.getResolutionStatus()).isEqualTo(SupplierHintResolutionStatus.RESOLVED);
        assertThat(hint.getResolvedProductId()).isEqualTo(PRODUCT_ID);
        assertThat(hint.getResolvedAt()).isEqualTo(NOW);
        assertThat(hint.getResolvedBy()).isEqualTo("catalog:EAN");
        verify(hints).save(hint);
    }

    @Test
    @DisplayName("an EAN catalog does not carry leaves the hint unresolved and intact")
    void unmatched_ean_stays_unresolved() {
        SupplierStockHint hint = pending("4012345678999");
        backlog(hint);
        replicaHolds("4012345678999");

        SupplierStockHintResolver.ResolutionPassResult result = resolver.runResolutionPass();

        assertThat(result.unresolved()).isEqualTo(1);
        assertThat(hint.getResolutionStatus()).isEqualTo(SupplierHintResolutionStatus.UNRESOLVED);
        assertThat(hint.getResolvedProductId()).isNull();
        // The vendor's statement is untouched; only our knowledge of what it refers to is missing.
        assertThat(hint.getAvailableQuantity()).isEqualTo(3);
        verify(hints).save(hint);
    }

    @Test
    @DisplayName("an article with no EAN is not matched on vendor codes")
    void article_without_ean_is_not_resolvable() {
        SupplierStockHint hint = pending(null);
        backlog(hint);
        when(productCodes.count()).thenReturn(1L);

        SupplierStockHintResolver.ResolutionPassResult result = resolver.runResolutionPass();

        assertThat(result.notResolvable()).isEqualTo(1);
        assertThat(hint.getResolutionStatus()).isEqualTo(SupplierHintResolutionStatus.NOT_RESOLVABLE);
        // Vendor and buyer article codes carry no uniqueness guarantee; nothing is guessed from them.
        verify(productCodes, never()).findByCodeTypeAndCode(any(), any());
    }

    @Test
    @DisplayName("an unseeded replica defers the pass instead of calling every hint unresolved")
    void empty_replica_defers() {
        SupplierStockHint hint = pending("4012345678901");
        backlog(hint);
        when(productCodes.count()).thenReturn(0L);

        SupplierStockHintResolver.ResolutionPassResult result = resolver.runResolutionPass();

        assertThat(result.deferred()).isEqualTo(1);
        // Still PENDING: "we have not been sent the codes" says nothing about our catalog data, so
        // it must not be recorded as though it did.
        assertThat(hint.getResolutionStatus()).isEqualTo(SupplierHintResolutionStatus.PENDING);
        verify(hints, never()).save(any());
        verify(productCodes, never()).findByCodeTypeAndCode(any(), any());
    }

    @Test
    @DisplayName("a code the replica holds twice is refused rather than guessed at")
    void duplicated_code_is_refused() {
        SupplierStockHint hint = pending("4012345678901");
        backlog(hint);
        replicaHolds(
                "4012345678901",
                productCode(PRODUCT_ID, "4012345678901"),
                productCode(UUID.fromString("018f0000-0000-7000-8000-000000000705"), "4012345678901"));

        SupplierStockHintResolver.ResolutionPassResult result = resolver.runResolutionPass();

        // pos-catalog constrains (codeType, code) at the source, so two rows is a replication
        // defect. Picking either product would attach a vendor's stock to the wrong article.
        assertThat(result.unresolved()).isEqualTo(1);
        assertThat(hint.getResolutionStatus()).isEqualTo(SupplierHintResolutionStatus.UNRESOLVED);
        assertThat(hint.getResolvedProductId()).isNull();
    }

    @Test
    @DisplayName("a pass that resolves nothing clears any product the row was still carrying")
    void failed_resolution_clears_a_stale_product() {
        SupplierStockHint hint = pending("4012345678999");
        // Re-queued after a snapshot changed its EAN, still carrying the earlier resolution.
        hint.setResolvedProductId(PRODUCT_ID);
        hint.setResolvedAt(NOW.minusSeconds(7200));
        hint.setResolvedBy("catalog:EAN");
        backlog(hint);
        replicaHolds("4012345678999");

        resolver.runResolutionPass();

        assertThat(hint.getResolutionStatus()).isEqualTo(SupplierHintResolutionStatus.UNRESOLVED);
        assertThat(hint.getResolvedProductId()).isNull();
        assertThat(hint.getResolvedAt()).isNull();
        assertThat(hint.getResolvedBy()).isNull();
    }

    @Test
    @DisplayName("an article that loses its EAN drops the product it had resolved to")
    void not_resolvable_clears_a_stale_product() {
        SupplierStockHint hint = pending(null);
        hint.setResolvedProductId(PRODUCT_ID);
        hint.setResolvedAt(NOW.minusSeconds(7200));
        hint.setResolvedBy("catalog:EAN");
        backlog(hint);

        resolver.runResolutionPass();

        assertThat(hint.getResolutionStatus()).isEqualTo(SupplierHintResolutionStatus.NOT_RESOLVABLE);
        assertThat(hint.getResolvedProductId()).isNull();
        assertThat(hint.getResolvedAt()).isNull();
        assertThat(hint.getResolvedBy()).isNull();
    }

    @Test
    @DisplayName("a pass is bounded by the configured batch size")
    void pass_is_bounded() {
        properties.getResolution().setBatchSize(50);
        backlog(pending("4012345678901"));
        replicaHolds("4012345678901");

        resolver.runResolutionPass();

        verify(hints).findByResolutionStatusOrderByFetchedAtAsc(SupplierHintResolutionStatus.PENDING, Limit.of(50));
    }

    @Test
    @DisplayName("the scheduled entry point never lets a failed pass escalate")
    void scheduled_pass_swallows_failures() {
        when(hints.findByResolutionStatusOrderByFetchedAtAsc(any(), any(Limit.class)))
                .thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> resolver.resolvePending()).doesNotThrowAnyException();
    }
}
