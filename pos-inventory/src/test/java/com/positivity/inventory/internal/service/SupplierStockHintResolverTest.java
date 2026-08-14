package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.client.CatalogProductCodeClient;
import com.positivity.inventory.internal.config.SupplierStockHintProperties;
import com.positivity.inventory.internal.entity.SupplierStockHint;
import com.positivity.inventory.internal.enums.SupplierHintAsOfSource;
import com.positivity.inventory.internal.enums.SupplierHintIdentityKind;
import com.positivity.inventory.internal.enums.SupplierHintResolutionStatus;
import com.positivity.inventory.internal.repository.SupplierStockHintRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.web.client.ResourceAccessException;

/**
 * Article resolution against pos-catalog's exact-match EAN lookup (CAP-322, #1312).
 *
 * <p>The contract worth holding: an article that cannot be resolved is retained and visible in
 * every case, and the reason it is unresolved is recorded rather than flattened — catalog does not
 * carry it, the vendor stated no EAN, or we could not ask.
 */
class SupplierStockHintResolverTest {

    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");
    private static final Clock TEST_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID PRODUCT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000701");
    private static final UUID VENDOR_ID = UUID.fromString("018f0000-0000-7000-8000-000000000702");

    private final SupplierStockHintRepository hints = mock(SupplierStockHintRepository.class);
    private final CatalogProductCodeClient catalog = mock(CatalogProductCodeClient.class);
    private final SupplierStockHintProperties properties = new SupplierStockHintProperties();

    private SupplierStockHintResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new SupplierStockHintResolver(hints, catalog, properties, TEST_CLOCK);
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
        when(catalog.findByCode("EAN", "4012345678901"))
                .thenReturn(Optional.of(new CatalogProductCodeClient.ProductCodeMatchDto(
                        PRODUCT_ID, "SKU-1", "Tyre", "EAN", "4012345678901")));

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
        when(catalog.findByCode("EAN", "4012345678999")).thenReturn(Optional.empty());

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

        SupplierStockHintResolver.ResolutionPassResult result = resolver.runResolutionPass();

        assertThat(result.notResolvable()).isEqualTo(1);
        assertThat(hint.getResolutionStatus()).isEqualTo(SupplierHintResolutionStatus.NOT_RESOLVABLE);
        // Vendor and buyer article codes carry no uniqueness guarantee; nothing is guessed from them.
        verify(catalog, never()).findByCode(any(), any());
    }

    @Test
    @DisplayName("a catalog that cannot be reached leaves the hint pending for the next pass")
    void unreachable_catalog_defers() {
        SupplierStockHint hint = pending("4012345678901");
        backlog(hint);
        when(catalog.findByCode("EAN", "4012345678901")).thenThrow(new ResourceAccessException("connection refused"));

        SupplierStockHintResolver.ResolutionPassResult result = resolver.runResolutionPass();

        assertThat(result.deferred()).isEqualTo(1);
        // Still PENDING: an outage says nothing about our catalog data, so it must not be recorded
        // as though it did.
        assertThat(hint.getResolutionStatus()).isEqualTo(SupplierHintResolutionStatus.PENDING);
        verify(hints, never()).save(any());
    }

    @Test
    @DisplayName("a pass is bounded by the configured batch size")
    void pass_is_bounded() {
        properties.getResolution().setBatchSize(50);
        backlog();

        resolver.runResolutionPass();

        verify(hints).findByResolutionStatusOrderByFetchedAtAsc(SupplierHintResolutionStatus.PENDING, Limit.of(50));
    }

    @Test
    @DisplayName("the scheduled entry point never lets a failed pass escalate")
    void scheduled_pass_swallows_failures() {
        when(hints.findByResolutionStatusOrderByFetchedAtAsc(any(), any(Limit.class)))
                .thenThrow(new IllegalStateException("boom"));

        resolver.resolvePending();
    }
}
