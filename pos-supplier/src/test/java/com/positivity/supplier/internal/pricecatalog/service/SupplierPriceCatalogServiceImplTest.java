package com.positivity.supplier.internal.pricecatalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.entity.PriceCatalogImportEntity;
import com.positivity.supplier.internal.entity.PriceCatalogUnmatchedLineEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.entity.SupplierScheduleLeaseEntity;
import com.positivity.supplier.internal.enums.PriceCatalogErrorCode;
import com.positivity.supplier.internal.enums.PriceCatalogImportStatus;
import com.positivity.supplier.internal.enums.UnmatchedLineReason;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.pricecatalog.service.model.PriceCatalogFreshnessView;
import com.positivity.supplier.internal.pricecatalog.service.model.PriceCatalogImportSummary;
import com.positivity.supplier.internal.pricecatalog.service.model.UnmatchedPriceCatalogLineView;
import com.positivity.supplier.internal.repository.PriceCatalogImportRepository;
import com.positivity.supplier.internal.repository.PriceCatalogUnmatchedLineRepository;
import com.positivity.supplier.internal.repository.SupplierEndpointBindingRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.internal.repository.SupplierScheduleLeaseRepository;
import com.positivity.supplier.internal.service.model.PagedResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * so every field the view promises has to survive the mapping. The freshness read additionally
 * carries the one policy decision this service owns: the staleness verdict against the configured
 * threshold (#1637 decision 3).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SupplierPriceCatalogServiceImpl")
class SupplierPriceCatalogServiceImplTest {

    private static final UUID PROFILE_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000b1");
    private static final UUID MANIFEST_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000c1");
    private static final UUID LINE_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000d1");
    private static final UUID BINDING_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000e1");
    private static final Instant FETCHED = Instant.parse("2026-08-16T09:00:00Z");
    private static final Instant COMPLETED = Instant.parse("2026-08-16T09:04:00Z");
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final Duration THRESHOLD = Duration.ofDays(7);

    @Mock
    private PriceCatalogImportRepository importRepository;

    @Mock
    private PriceCatalogUnmatchedLineRepository unmatchedLineRepository;

    @Mock
    private SupplierProfileRepository profileRepository;

    @Mock
    private SupplierEndpointBindingRepository bindingRepository;

    @Mock
    private SupplierScheduleLeaseRepository scheduleLeaseRepository;

    @Mock
    private PriceCatalogImporter importService;

    @Mock
    private QuarantineReapplier reapplicationService;

    private SupplierPriceCatalogServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SupplierPriceCatalogServiceImpl(
                importRepository,
                unmatchedLineRepository,
                profileRepository,
                bindingRepository,
                scheduleLeaseRepository,
                importService,
                reapplicationService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                THRESHOLD);
    }

    private static SupplierProfileEntity profile() {
        SupplierProfileEntity profile = new SupplierProfileEntity();
        profile.setVendorProfileId(PROFILE_ID);
        profile.setSupplierRef("michelin-eu");
        profile.setEnabled(true);
        return profile;
    }

    private static PriceCatalogImportEntity importRow(PriceCatalogImportStatus status) {
        return PriceCatalogImportEntity.builder()
                .importManifestId(MANIFEST_ID)
                .vendorProfileId(PROFILE_ID)
                .bindingId(BINDING_ID)
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
                .errorCode(status == PriceCatalogImportStatus.FAILED ? PriceCatalogErrorCode.FETCH_FAILED : null)
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
            assertThat(s.bindingId()).isEqualTo(BINDING_ID);
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
            assertThat(s.errorCode()).isNull();
            // Full-snapshot protocol: no window, no checkpoint (#1637 decision 5).
            assertThat(s.windowFrom()).isNull();
            assertThat(s.windowTo()).isNull();
            assertThat(s.checkpointState()).isNull();
            assertThat(s.checkpointAt()).isNull();
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
        @DisplayName("pages the import history and carries a failed run's detail and error code through")
        void pagesImportHistory() {
            PriceCatalogImportEntity failed = importRow(PriceCatalogImportStatus.FAILED);
            when(importRepository.search(eq(PROFILE_ID), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(failed), PageRequest.of(1, 20), 21));

            PagedResponse<PriceCatalogImportSummary> page =
                    service.listImports(PROFILE_ID, null, null, null, null, 1, 20);

            assertThat(page.page()).isEqualTo(1);
            assertThat(page.size()).isEqualTo(20);
            assertThat(page.totalElements()).isEqualTo(21);
            assertThat(page.items()).singleElement().satisfies(item -> {
                assertThat(item.status()).isEqualTo("FAILED");
                assertThat(item.completedAt()).isNull();
                assertThat(item.failureDetail()).isEqualTo("vendor returned 503");
                assertThat(item.errorCode()).isEqualTo("FETCH_FAILED");
            });
        }

        @Test
        @DisplayName("passes the run filters through untouched")
        void passesImportFiltersThrough() {
            Instant from = Instant.parse("2026-08-01T00:00:00Z");
            Instant to = Instant.parse("2026-09-01T00:00:00Z");
            when(importRepository.search(any(), any(), any(), any(), any(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

            service.listImports(PROFILE_ID, BINDING_ID, PriceCatalogImportStatus.FAILED, from, to, 0, 50);

            verify(importRepository)
                    .search(
                            eq(PROFILE_ID),
                            eq(BINDING_ID),
                            eq(PriceCatalogImportStatus.FAILED),
                            eq(from),
                            eq(to),
                            any(Pageable.class));
        }

        @Test
        @DisplayName("defaults the quarantine worklist to unresolved lines, mapping the operator's identifiers")
        void pagesUnmatchedLines() {
            when(unmatchedLineRepository.search(
                            eq(PROFILE_ID), eq(false), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(unmatchedRow()), PageRequest.of(0, 50), 1));

            PagedResponse<UnmatchedPriceCatalogLineView> page =
                    service.listUnmatchedLines(PROFILE_ID, null, null, null, null, null, 0, 50);

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
        @DisplayName("escapes LIKE metacharacters in the identifier search so a pasted code is a literal")
        void escapesTheSearchPattern() {
            when(unmatchedLineRepository.search(any(), eq(false), any(), any(), any(), any(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

            service.listUnmatchedLines(
                    PROFILE_ID, UnmatchedLineReason.NO_CATALOG_MATCH, "MI_225%45!17", null, null, false, 0, 50);

            verify(unmatchedLineRepository)
                    .search(
                            eq(PROFILE_ID),
                            eq(false),
                            eq(UnmatchedLineReason.NO_CATALOG_MATCH),
                            eq("%mi!_225!%45!!17%"),
                            isNull(),
                            isNull(),
                            any(Pageable.class));
        }

        @Test
        @DisplayName("a blank search means no search rather than matching everything twice over")
        void blankSearchIsNoSearch() {
            when(unmatchedLineRepository.search(any(), eq(true), any(), any(), any(), any(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

            service.listUnmatchedLines(PROFILE_ID, null, "   ", null, null, true, 0, 50);

            verify(unmatchedLineRepository)
                    .search(eq(PROFILE_ID), eq(true), isNull(), isNull(), isNull(), isNull(), any(Pageable.class));
        }

        @Test
        @DisplayName("an empty page reports its paging rather than pretending there is one row")
        void emptyPage() {
            when(importRepository.search(any(), any(), any(), any(), any(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            PagedResponse<PriceCatalogImportSummary> page =
                    service.listImports(PROFILE_ID, null, null, null, null, 0, 20);

            assertThat(page.items()).isEmpty();
            assertThat(page.totalElements()).isZero();
            assertThat(page.totalPages()).isZero();
        }
    }

    @Nested
    @DisplayName("getFreshness")
    class GetFreshness {

        private SupplierEndpointBindingEntity binding(boolean enabled, String cron) {
            SupplierEndpointBindingEntity binding = new SupplierEndpointBindingEntity();
            binding.setId(BINDING_ID);
            binding.setVendorProfileId(PROFILE_ID);
            binding.setCapability(SupplierCapability.PRICE_CATALOG);
            binding.setEnabled(enabled);
            binding.setScheduleCron(cron);
            return binding;
        }

        @BeforeEach
        void profileExists() {
            when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile()));
            when(bindingRepository.findByVendorProfileIdAndCapability(PROFILE_ID, SupplierCapability.PRICE_CATALOG))
                    .thenReturn(Optional.empty());
            when(unmatchedLineRepository.countByVendorProfileIdAndResolvedAtIsNull(PROFILE_ID))
                    .thenReturn(0L);
        }

        @Test
        @DisplayName("keeps the vendor's document date and the platform's fetch time as separate facts")
        void aggregatesTheSeparateFacts() {
            when(importRepository.findLatestSourceDocumentDate(PROFILE_ID, PriceCatalogImportStatus.COMPLETED))
                    .thenReturn(LocalDate.of(2026, 8, 16));
            when(importRepository.findLastFetchedAt(PROFILE_ID)).thenReturn(Instant.parse("2026-08-18T03:00:00Z"));
            when(importRepository.findLastCompletedAt(PROFILE_ID)).thenReturn(Instant.parse("2026-08-16T09:04:00Z"));
            when(unmatchedLineRepository.countByVendorProfileIdAndResolvedAtIsNull(PROFILE_ID))
                    .thenReturn(42L);

            PriceCatalogFreshnessView view = service.getFreshness(PROFILE_ID);

            assertThat(view.vendorProfileId()).isEqualTo(PROFILE_ID);
            assertThat(view.latestEffectiveDate()).isEqualTo(LocalDate.of(2026, 8, 16));
            assertThat(view.lastFetchedAt()).isEqualTo(Instant.parse("2026-08-18T03:00:00Z"));
            assertThat(view.lastCompletedAt()).isEqualTo(Instant.parse("2026-08-16T09:04:00Z"));
            assertThat(view.unresolvedUnmatchedCount()).isEqualTo(42L);
            assertThat(view.stalenessThreshold()).isEqualTo("PT168H");
            assertThat(view.stale()).isFalse();
        }

        @Test
        @DisplayName("a never-imported profile is stale with null timestamps, not an error")
        void neverImportedIsStale() {
            PriceCatalogFreshnessView view = service.getFreshness(PROFILE_ID);

            assertThat(view.latestEffectiveDate()).isNull();
            assertThat(view.lastFetchedAt()).isNull();
            assertThat(view.lastCompletedAt()).isNull();
            assertThat(view.unresolvedUnmatchedCount()).isZero();
            assertThat(view.stale()).isTrue();
            assertThat(view.bindings()).isEmpty();
        }

        @Test
        @DisplayName("a completed import exactly at the threshold boundary is not yet stale")
        void thresholdBoundaryIsNotStale() {
            when(importRepository.findLastCompletedAt(PROFILE_ID)).thenReturn(NOW.minus(THRESHOLD));

            assertThat(service.getFreshness(PROFILE_ID).stale()).isFalse();
        }

        @Test
        @DisplayName("a completed import just past the threshold is stale")
        void justPastTheThresholdIsStale() {
            when(importRepository.findLastCompletedAt(PROFILE_ID))
                    .thenReturn(NOW.minus(THRESHOLD).minusSeconds(1));

            assertThat(service.getFreshness(PROFILE_ID).stale()).isTrue();
        }

        @Test
        @DisplayName("carries the binding's schedule and lease state when a lease row exists")
        void bindingWithLease() {
            when(bindingRepository.findByVendorProfileIdAndCapability(PROFILE_ID, SupplierCapability.PRICE_CATALOG))
                    .thenReturn(Optional.of(binding(true, "0 0 3 * * *")));
            SupplierScheduleLeaseEntity lease = SupplierScheduleLeaseEntity.builder()
                    .bindingId(BINDING_ID)
                    .vendorProfileId(PROFILE_ID)
                    .capability(SupplierCapability.PRICE_CATALOG)
                    .checkpointAt(Instant.parse("2026-08-18T03:05:00Z"))
                    .lastRunOutcome("COMPLETED")
                    .lastRunStartedAt(Instant.parse("2026-08-18T03:00:00Z"))
                    .build();
            when(scheduleLeaseRepository.findById(BINDING_ID)).thenReturn(Optional.of(lease));

            PriceCatalogFreshnessView view = service.getFreshness(PROFILE_ID);

            assertThat(view.bindings()).singleElement().satisfies(b -> {
                assertThat(b.bindingId()).isEqualTo(BINDING_ID);
                assertThat(b.scheduleCron()).isEqualTo("0 0 3 * * *");
                assertThat(b.enabled()).isTrue();
                assertThat(b.checkpointAt()).isEqualTo(Instant.parse("2026-08-18T03:05:00Z"));
                assertThat(b.lastRunOutcome()).isEqualTo("COMPLETED");
                assertThat(b.lastRunStartedAt()).isEqualTo(Instant.parse("2026-08-18T03:00:00Z"));
            });
        }

        @Test
        @DisplayName("a binding no scheduled run ever claimed reports null lease facts, not an error")
        void bindingWithoutLease() {
            when(bindingRepository.findByVendorProfileIdAndCapability(PROFILE_ID, SupplierCapability.PRICE_CATALOG))
                    .thenReturn(Optional.of(binding(false, null)));
            when(scheduleLeaseRepository.findById(BINDING_ID)).thenReturn(Optional.empty());

            PriceCatalogFreshnessView view = service.getFreshness(PROFILE_ID);

            assertThat(view.bindings()).singleElement().satisfies(b -> {
                assertThat(b.enabled()).isFalse();
                assertThat(b.scheduleCron()).isNull();
                assertThat(b.checkpointAt()).isNull();
                assertThat(b.lastRunOutcome()).isNull();
                assertThat(b.lastRunStartedAt()).isNull();
            });
        }

        @Test
        @DisplayName("refuses an unknown profile rather than reporting a nonexistent feed as stale")
        void unknownProfileIsRefused() {
            when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getFreshness(PROFILE_ID))
                    .isInstanceOf(SupplierConfigurationException.class)
                    .hasMessageContaining("No vendor profile exists with id");
        }
    }

    @Nested
    @DisplayName("runImport")
    class RunImport {

        @Test
        @DisplayName("runs the same import path the scheduler takes, keyed by the profile's supplier ref")
        void runsTheImport() {
            when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile()));
            when(importService.runImport(new SupplierRef("michelin-eu")))
                    .thenReturn(importRow(PriceCatalogImportStatus.COMPLETED));

            PriceCatalogImportSummary summary = service.runImport(PROFILE_ID);

            assertThat(summary.status()).isEqualTo("COMPLETED");
            // The importer resolves the PRICE_CATALOG binding itself, so an operator-triggered run
            // carries the binding id exactly as a scheduled one does (#1637 decision 4).
            assertThat(summary.bindingId()).isEqualTo(BINDING_ID);
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
