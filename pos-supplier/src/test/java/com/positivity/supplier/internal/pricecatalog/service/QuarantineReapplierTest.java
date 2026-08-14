package com.positivity.supplier.internal.pricecatalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.supplier.internal.entity.PriceCatalogImportEntity;
import com.positivity.supplier.internal.entity.PriceCatalogUnmatchedLineEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.enums.PriceCatalogMatchMethod;
import com.positivity.supplier.internal.enums.UnmatchedLineReason;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.pricecatalog.service.QuarantineReapplier.ResolvedLine;
import com.positivity.supplier.internal.repository.PriceCatalogUnmatchedLineRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Quarantine re-application (#1310, ADR-0053 §5)")
class QuarantineReapplierTest {

    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d");
    private static final UUID ORIGIN_IMPORT = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private SupplierProfileRepository profileRepository;

    @Mock
    private PriceCatalogUnmatchedLineRepository unmatchedLineRepository;

    @Mock
    private ProductCodeResolver productCodeResolver;

    @Mock
    private QuarantineReapplicationWriter reapplicationWriter;

    private QuarantineReapplier service;

    @BeforeEach
    void setUp() {
        service = new QuarantineReapplier(
                profileRepository, unmatchedLineRepository, productCodeResolver, reapplicationWriter, CLOCK);
        ReflectionTestUtils.setField(service, "batchSize", 2000);
        ReflectionTestUtils.setField(service, "chunkSize", 500);

        SupplierProfileEntity profile = new SupplierProfileEntity();
        profile.setVendorProfileId(PROFILE_ID);
        profile.setSupplierRef("michelin-eu");
        profile.setEnabled(true);
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
        when(reapplicationWriter.commit(any(), anyList(), any(), anyInt()))
                .thenReturn(PriceCatalogImportEntity.builder()
                        .importManifestId(UUID.randomUUID())
                        .vendorProfileId(PROFILE_ID)
                        .linesMatched(1)
                        .build());
    }

    private static PriceCatalogUnmatchedLineEntity quarantined(String ean, String xref, UnmatchedLineReason reason) {
        return PriceCatalogUnmatchedLineEntity.builder()
                .unmatchedLineId(UUID.randomUUID())
                .importManifestId(ORIGIN_IMPORT)
                .vendorProfileId(PROFILE_ID)
                .positionNumber(7)
                .articleEan(ean)
                .supplierArticleCode("999908")
                .xReferenceCode(xref)
                .reason(reason)
                .netPrice(new BigDecimal("90.00"))
                .grossPrice(new BigDecimal("100.00"))
                .effectiveFrom(LocalDate.of(2026, 2, 1))
                .buyerAccountNumber("30012456")
                .countryCode("SE")
                .currency("SEK")
                .sourceDocumentId("PRICAT-1")
                .sourceDocumentDate(LocalDate.of(2026, 1, 30))
                .fetchedAt(Instant.parse("2026-08-13T08:00:00Z"))
                .build();
    }

    private void candidates(PriceCatalogUnmatchedLineEntity... rows) {
        when(unmatchedLineRepository.findByVendorProfileIdAndResolvedAtIsNullAndReasonIn(
                        eq(PROFILE_ID), anyList(), any(Pageable.class)))
                .thenReturn(List.of(rows));
    }

    @Test
    void appliesALineThatTheCatalogCanNowResolve() {
        candidates(quarantined("3528709999083", null, UnmatchedLineReason.NO_CATALOG_MATCH));
        when(productCodeResolver.resolve("EAN", "3528709999083"))
                .thenReturn(new ProductCodeResolver.Resolution.Matched(PRODUCT_ID));

        assertThat(service.reapply(PROFILE_ID)).isPresent();

        ArgumentCaptor<List<ResolvedLine>> captor = ArgumentCaptor.forClass(List.class);
        verify(reapplicationWriter).commit(any(), captor.capture(), any(), eq(500));
        assertThat(captor.getValue()).singleElement().satisfies(line -> {
            assertThat(line.productId()).isEqualTo(PRODUCT_ID);
            assertThat(line.method()).isEqualTo(PriceCatalogMatchMethod.EAN);
            // Rebuilt from the row's own stored values — no vendor call (ADR-0053 §5).
            assertThat(line.entry().netPrice()).isEqualByComparingTo(new BigDecimal("90.00"));
            assertThat(line.entry().sourceDocumentId()).isEqualTo("PRICAT-1");
        });
    }

    @Test
    void recoversALineThatWasQuarantinedOnlyBecauseTheReplicaWasUnavailable() {
        candidates(quarantined("3528709999083", null, UnmatchedLineReason.CATALOG_UNAVAILABLE));
        when(productCodeResolver.resolve("EAN", "3528709999083"))
                .thenReturn(new ProductCodeResolver.Resolution.Matched(PRODUCT_ID));

        assertThat(service.reapply(PROFILE_ID)).isPresent();
    }

    @Test
    void fallsBackToTheCrossReferenceCodeExactlyAsTheImportDoes() {
        candidates(quarantined(null, "0123456789012", UnmatchedLineReason.NO_CATALOG_MATCH));
        when(productCodeResolver.resolve("UPC", "0123456789012"))
                .thenReturn(new ProductCodeResolver.Resolution.Matched(PRODUCT_ID));

        assertThat(service.reapply(PROFILE_ID)).isPresent();
    }

    @Test
    void leavesALineOpenWhenTheCatalogStillCannotResolveIt() {
        candidates(quarantined("3528709999083", null, UnmatchedLineReason.NO_CATALOG_MATCH));
        when(productCodeResolver.resolve("EAN", "3528709999083"))
                .thenReturn(new ProductCodeResolver.Resolution.NotFound());

        assertThat(service.reapply(PROFILE_ID)).isEmpty();
        verify(reapplicationWriter, never()).commit(any(), anyList(), any(), anyInt());
    }

    @Test
    void leavesAnAmbiguousLineOpenRatherThanPickingAProduct() {
        candidates(quarantined("3528709999083", null, UnmatchedLineReason.AMBIGUOUS_CATALOG_MATCH));
        when(productCodeResolver.resolve("EAN", "3528709999083"))
                .thenReturn(new ProductCodeResolver.Resolution.Ambiguous("two products"));

        assertThat(service.reapply(PROFILE_ID)).isEmpty();
    }

    @Test
    void neverRetriesReasonsNoCatalogFixCanRescue() {
        service.reapply(PROFILE_ID);

        ArgumentCaptor<List<UnmatchedLineReason>> captor = ArgumentCaptor.forClass(List.class);
        verify(unmatchedLineRepository)
                .findByVendorProfileIdAndResolvedAtIsNullAndReasonIn(eq(PROFILE_ID), captor.capture(), any());
        assertThat(captor.getValue())
                .doesNotContain(UnmatchedLineReason.NO_IDENTIFIER, UnmatchedLineReason.MALFORMED_LINE)
                .containsExactlyInAnyOrder(
                        UnmatchedLineReason.NO_CATALOG_MATCH,
                        UnmatchedLineReason.AMBIGUOUS_CATALOG_MATCH,
                        UnmatchedLineReason.CATALOG_UNAVAILABLE);
    }

    @Test
    void doesNothingWhenTheQuarantineIsEmpty() {
        when(unmatchedLineRepository.findByVendorProfileIdAndResolvedAtIsNullAndReasonIn(
                        eq(PROFILE_ID), anyList(), any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(service.reapply(PROFILE_ID)).isEmpty();
        verify(reapplicationWriter, never()).commit(any(), anyList(), any(), anyInt());
    }

    @Test
    void isANoOpOnASecondRunBecauseClosedLinesAreNoLongerCandidates() {
        // The repository filters on resolvedAt IS NULL, so a line closed by the previous run simply
        // is not returned again — the idempotency is in the query, not in a flag the caller must
        // remember to check.
        candidates(quarantined("3528709999083", null, UnmatchedLineReason.NO_CATALOG_MATCH));
        when(productCodeResolver.resolve("EAN", "3528709999083"))
                .thenReturn(new ProductCodeResolver.Resolution.Matched(PRODUCT_ID));
        assertThat(service.reapply(PROFILE_ID)).isPresent();

        when(unmatchedLineRepository.findByVendorProfileIdAndResolvedAtIsNullAndReasonIn(
                        eq(PROFILE_ID), anyList(), any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(service.reapply(PROFILE_ID)).isEmpty();
    }

    @Test
    void refusesAnUnknownProfileRatherThanSilentlyDoingNothing() {
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reapply(PROFILE_ID)).isInstanceOf(SupplierConfigurationException.class);
    }
}
