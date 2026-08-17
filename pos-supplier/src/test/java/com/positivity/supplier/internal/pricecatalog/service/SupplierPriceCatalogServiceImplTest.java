package com.positivity.supplier.internal.pricecatalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.entity.PriceCatalogImportEntity;
import com.positivity.supplier.internal.entity.PriceCatalogUnmatchedLineEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.enums.PriceCatalogImportStatus;
import com.positivity.supplier.internal.enums.UnmatchedLineReason;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.repository.PriceCatalogImportRepository;
import com.positivity.supplier.internal.repository.PriceCatalogUnmatchedLineRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.service.model.PagedResponse;
import com.positivity.supplier.service.model.PriceCatalogImportSummary;
import com.positivity.supplier.service.model.UnmatchedPriceCatalogLineView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Import bookkeeping and the on-demand trigger for vendor price catalogs (ADR-0053 §7).
 *
 * <p>The reads are thin projections, but they are the projections an operator uses to tell a run
 * that completed from one that failed, and a line that went unmatched from one that was resolved —
 * so every field the view promises has to survive the mapping.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SupplierPriceCatalogServiceImpl")
class SupplierPriceCatalogServiceImplTest {

    private static final UUID PROFILE_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000b1");
    private static final UUID MANIFEST_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000c1");
    private static final UUID LINE_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000d1");
    private static final Instant FETCHED = Instant.parse("2026-08-16T09:00:00Z");
    private static final Instant COMPLETED = Instant.parse("2026-08-16T09:04:00Z");

    @Mock
    private PriceCatalogImportRepository importRepository;

    @Mock
    private PriceCatalogUnmatchedLineRepository unmatchedLineRepository;

    @Mock
    private SupplierProfileRepository profileRepository;

    @Mock
    private PriceCatalogImporter importService;

    @Mock
    private QuarantineReapplier reapplicationService;

    private SupplierPriceCatalogServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SupplierPriceCatalogServiceImpl(
                importRepository, unmatchedLineRepository, profileRepository, importService, reapplicationService);
    }

    private static PriceCatalogImportEntity importRow(PriceCatalogImportStatus status) {
        return PriceCatalogImportEntity.builder()
                .importManifestId(MANIFEST_ID)
                .vendorProfileId(PROFILE_ID)
                .supplierRef("michelin-eu")
                .status(status)
                .fetchedAt(FETCHED)
                .completedAt(status == PriceCatalogImportStatus.COMPLETED ? COMPLETED : null)
                .linesFetched(1200)
                .linesMatched(1180)
                .linesUnmatched(15)
                .linesDuplicate(5)
                .chunkCount(3)
                .sourceDocumentId("PRICAT-88")
                .sourceDocumentDate(LocalDate.of(2026, 8, 16))
                .countryCode("DE")
                .currency("EUR")
                .failureDetail(status == PriceCatalogImportStatus.FAILED ? "vendor returned 503" : null)
                .build();
    }

    private static PriceCatalogUnmatchedLineEntity unmatchedRow() {
        return PriceCatalogUnmatchedLineEntity.builder()
                .unmatchedLineId(LINE_ID)
                .importManifestId(MANIFEST_ID)
                .vendorProfileId(PROFILE_ID)
                .positionNumber(17)
                .articleEan("4001861234567")
                .supplierArticleCode("MI-225-45-17")
                .xReferenceCode("XREF-9")
                .reason(UnmatchedLineReason.NO_CATALOG_MATCH)
                .reasonDetail("no product carries this EAN")
                .netPrice(new BigDecimal("81.40"))
                .grossPrice(new BigDecimal("96.87"))
                .effectiveFrom(LocalDate.of(2026, 9, 1))
                .currency("EUR")
                .fetchedAt(FETCHED)
                .resolvedAt(null)
                .build();
    }

    @Nested
    @DisplayName("findLatestImport")
    class FindLatestImport {

        @Test
        @DisplayName("maps every field of the newest completed run")
        void mapsTheLatestCompletedRun() {
            when(importRepository.findFirstByVendorProfileIdAndStatusOrderByFetchedAtDesc(
                            PROFILE_ID, PriceCatalogImportStatus.COMPLETED))
                    .thenReturn(Optional.of(importRow(PriceCatalogImportStatus.COMPLETED)));

            Optional<PriceCatalogImportSummary> summary = service.findLatestImport(PROFILE_ID);

            assertThat(summary).isPresent();
            PriceCatalogImportSummary s = summary.get();
            assertThat(s.importManifestId()).isEqualTo(MANIFEST_ID);
            assertThat(s.vendorProfileId()).isEqualTo(PROFILE_ID);
            assertThat(s.supplierRef()).isEqualTo("michelin-eu");
            assertThat(s.status()).isEqualTo("COMPLETED");
            assertThat(s.fetchedAt()).isEqualTo(FETCHED);
            assertThat(s.completedAt()).isEqualTo(COMPLETED);
            assertThat(s.linesFetched()).isEqualTo(1200);
            assertThat(s.linesMatched()).isEqualTo(1180);
            assertThat(s.linesUnmatched()).isEqualTo(15);
            assertThat(s.linesDuplicate()).isEqualTo(5);
            assertThat(s.chunkCount()).isEqualTo(3);
            assertThat(s.sourceDocumentId()).isEqualTo("PRICAT-88");
            assertThat(s.sourceDocumentDate()).isEqualTo(LocalDate.of(2026, 8, 16));
            assertThat(s.countryCode()).isEqualTo("DE");
            assertThat(s.currency()).isEqualTo("EUR");
            assertThat(s.failureDetail()).isNull();
        }

        @Test
        @DisplayName("returns empty when the profile has never completed a run")
        void noCompletedRun() {
            when(importRepository.findFirstByVendorProfileIdAndStatusOrderByFetchedAtDesc(any(), any()))
                    .thenReturn(Optional.empty());

            assertThat(service.findLatestImport(PROFILE_ID)).isEmpty();
        }
    }

    @Nested
    @DisplayName("listing")
    class Listing {

        @Test
        @DisplayName("pages the import history and carries a failed run's detail through")
        void pagesImportHistory() {
            PriceCatalogImportEntity failed = importRow(PriceCatalogImportStatus.FAILED);
            when(importRepository.findByVendorProfileIdOrderByFetchedAtDesc(eq(PROFILE_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(failed), PageRequest.of(1, 20), 21));

            PagedResponse<PriceCatalogImportSummary> page = service.listImports(PROFILE_ID, 1, 20);

            assertThat(page.page()).isEqualTo(1);
            assertThat(page.size()).isEqualTo(20);
            assertThat(page.totalElements()).isEqualTo(21);
            assertThat(page.items()).singleElement().satisfies(item -> {
                assertThat(item.status()).isEqualTo("FAILED");
                assertThat(item.completedAt()).isNull();
                assertThat(item.failureDetail()).isEqualTo("vendor returned 503");
            });
        }

        @Test
        @DisplayName("lists only unresolved quarantine lines, mapping the identifiers an operator matches on")
        void pagesUnmatchedLines() {
            when(unmatchedLineRepository.findByVendorProfileIdAndResolvedAtIsNullOrderByCreatedAtDesc(
                            eq(PROFILE_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(unmatchedRow()), PageRequest.of(0, 50), 1));

            PagedResponse<UnmatchedPriceCatalogLineView> page = service.listUnmatchedLines(PROFILE_ID, 0, 50);

            assertThat(page.items()).singleElement().satisfies(line -> {
                assertThat(line.unmatchedLineId()).isEqualTo(LINE_ID);
                assertThat(line.importManifestId()).isEqualTo(MANIFEST_ID);
                assertThat(line.positionNumber()).isEqualTo(17);
                assertThat(line.articleEan()).isEqualTo("4001861234567");
                assertThat(line.supplierArticleCode()).isEqualTo("MI-225-45-17");
                assertThat(line.xReferenceCode()).isEqualTo("XREF-9");
                assertThat(line.reason()).isEqualTo("NO_CATALOG_MATCH");
                assertThat(line.reasonDetail()).isEqualTo("no product carries this EAN");
                assertThat(line.netPrice()).isEqualByComparingTo("81.40");
                assertThat(line.grossPrice()).isEqualByComparingTo("96.87");
                assertThat(line.effectiveFrom()).isEqualTo(LocalDate.of(2026, 9, 1));
                assertThat(line.currency()).isEqualTo("EUR");
                assertThat(line.resolvedAt()).isNull();
            });
        }

        @Test
        @DisplayName("an empty page reports its paging rather than pretending there is one row")
        void emptyPage() {
            when(importRepository.findByVendorProfileIdOrderByFetchedAtDesc(any(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            PagedResponse<PriceCatalogImportSummary> page = service.listImports(PROFILE_ID, 0, 20);

            assertThat(page.items()).isEmpty();
            assertThat(page.totalElements()).isZero();
            assertThat(page.totalPages()).isZero();
        }
    }

    @Nested
    @DisplayName("runImport")
    class RunImport {

        @Test
        @DisplayName("runs the same import path the scheduler takes, keyed by the profile's supplier ref")
        void runsTheImport() {
            SupplierProfileEntity profile = new SupplierProfileEntity();
            profile.setVendorProfileId(PROFILE_ID);
            profile.setSupplierRef("michelin-eu");
            when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
            when(importService.runImport(new SupplierRef("michelin-eu")))
                    .thenReturn(importRow(PriceCatalogImportStatus.COMPLETED));

            assertThat(service.runImport(PROFILE_ID).status()).isEqualTo("COMPLETED");
            verify(importService).runImport(new SupplierRef("michelin-eu"));
        }

        @Test
        @DisplayName("refuses an unknown profile rather than importing nothing quietly")
        void unknownProfileIsRefused() {
            when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.runImport(PROFILE_ID))
                    .isInstanceOf(SupplierConfigurationException.class)
                    .hasMessageContaining("No vendor profile exists with id");
            verify(importService, never()).runImport(any());
        }
    }

    @Nested
    @DisplayName("reapplyQuarantine")
    class ReapplyQuarantine {

        @Test
        @DisplayName("maps every run the reapplication produced")
        void mapsReapplicationRuns() {
            when(reapplicationService.reapply(PROFILE_ID))
                    .thenReturn(List.of(
                            importRow(PriceCatalogImportStatus.COMPLETED), importRow(PriceCatalogImportStatus.FAILED)));

            assertThat(service.reapplyQuarantine(PROFILE_ID))
                    .extracting(PriceCatalogImportSummary::status)
                    .containsExactly("COMPLETED", "FAILED");
        }

        @Test
        @DisplayName("nothing quarantined is an empty answer, not a failure")
        void nothingToReapply() {
            when(reapplicationService.reapply(PROFILE_ID)).thenReturn(List.of());

            assertThat(service.reapplyQuarantine(PROFILE_ID)).isEmpty();
        }
    }
}
