package com.positivity.supplier.internal.pricecatalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.supplier.SupplierPriceCatalogImportCompletedV1;
import com.positivity.domainevents.supplier.SupplierPriceCatalogUpdatedV1;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.SupplierPriceCatalogEntry;
import com.positivity.supplier.internal.entity.PriceCatalogEntryEntity;
import com.positivity.supplier.internal.entity.PriceCatalogImportEntity;
import com.positivity.supplier.internal.entity.PriceCatalogUnmatchedLineEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.enums.PriceCatalogImportStatus;
import com.positivity.supplier.internal.enums.PriceCatalogMatchMethod;
import com.positivity.supplier.internal.enums.UnmatchedLineReason;
import com.positivity.supplier.internal.pricecatalog.service.QuarantineReapplier.ResolvedLine;
import com.positivity.supplier.internal.repository.PriceCatalogEntryRepository;
import com.positivity.supplier.internal.repository.PriceCatalogImportRepository;
import com.positivity.supplier.internal.repository.PriceCatalogUnmatchedLineRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Quarantine re-application persistence and events (#1310)")
class QuarantineReapplicationWriterTest {

    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d");
    private static final UUID ORIGIN_IMPORT = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final UUID NEW_MANIFEST = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a60");
    private static final Instant VENDOR_FETCHED_AT = Instant.parse("2026-08-13T08:00:00Z");
    private static final Instant APPLIED_AT = Instant.parse("2026-08-14T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(APPLIED_AT, ZoneOffset.UTC);

    @Mock
    private PriceCatalogImportRepository importRepository;

    @Mock
    private PriceCatalogEntryRepository entryRepository;

    @Mock
    private PriceCatalogUnmatchedLineRepository unmatchedLineRepository;

    @Mock
    private SupplierOutboxEventWriter outboxWriter;

    private QuarantineReapplicationWriter writer;

    @BeforeEach
    void setUp() {
        writer = new QuarantineReapplicationWriter(
                importRepository, entryRepository, unmatchedLineRepository, outboxWriter, CLOCK);

        when(importRepository.findById(ORIGIN_IMPORT))
                .thenReturn(Optional.of(PriceCatalogImportEntity.builder()
                        .importManifestId(ORIGIN_IMPORT)
                        .vendorProfileId(PROFILE_ID)
                        .protocolFamily(ProtocolFamily.EDIWHEEL_B)
                        .protocolVersion("B4_0")
                        .status(PriceCatalogImportStatus.COMPLETED)
                        .linesFetched(100)
                        .linesMatched(80)
                        .linesUnmatched(20)
                        .chunkCount(1)
                        .build()));
        when(importRepository.save(any(PriceCatalogImportEntity.class))).thenAnswer(inv -> {
            PriceCatalogImportEntity entity = inv.getArgument(0);
            if (entity.getImportManifestId() == null) {
                entity.setImportManifestId(NEW_MANIFEST);
            }
            return entity;
        });
        when(entryRepository.save(any(PriceCatalogEntryEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(unmatchedLineRepository.save(any(PriceCatalogUnmatchedLineEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static SupplierProfileEntity profile() {
        SupplierProfileEntity profile = new SupplierProfileEntity();
        profile.setVendorProfileId(PROFILE_ID);
        profile.setSupplierRef("michelin-eu");
        profile.setEnabled(true);
        return profile;
    }

    private static ResolvedLine resolvedLine(int position) {
        PriceCatalogUnmatchedLineEntity row = PriceCatalogUnmatchedLineEntity.builder()
                .unmatchedLineId(UUID.randomUUID())
                .importManifestId(ORIGIN_IMPORT)
                .vendorProfileId(PROFILE_ID)
                .positionNumber(position)
                .articleEan("352870999908" + position)
                .supplierArticleCode("99990" + position)
                .reason(UnmatchedLineReason.NO_CATALOG_MATCH)
                .netPrice(new BigDecimal("90.00"))
                .effectiveFrom(LocalDate.of(2026, 2, 1))
                .buyerAccountNumber("30012456")
                .countryCode("SE")
                .currency("SEK")
                .sourceDocumentId("PRICAT-1")
                .sourceDocumentDate(LocalDate.of(2026, 1, 30))
                .fetchedAt(VENDOR_FETCHED_AT)
                .build();
        SupplierPriceCatalogEntry entry = new SupplierPriceCatalogEntry(
                row.getArticleEan(),
                row.getSupplierArticleCode(),
                null,
                null,
                null,
                row.getNetPrice(),
                null,
                null,
                row.getEffectiveFrom(),
                row.getCountryCode(),
                row.getCurrency(),
                row.getSourceDocumentId(),
                row.getSourceDocumentDate(),
                row.getImportManifestId(),
                position);
        return new ResolvedLine(row, entry, PRODUCT_ID, PriceCatalogMatchMethod.EAN);
    }

    @SuppressWarnings("unchecked")
    private List<DomainEventEnvelope<?>> capturedEvents() {
        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(outboxWriter, org.mockito.Mockito.atLeastOnce()).publish(eq("supplier.events.v1"), captor.capture());
        return captor.getAllValues();
    }

    @Test
    void createsItsOwnManifestPointingAtTheImportItHealed() {
        PriceCatalogImportEntity manifest = writer.commit(profile(), List.of(resolvedLine(1)), APPLIED_AT, 500);

        assertThat(manifest.getReappliedFromImportId()).isEqualTo(ORIGIN_IMPORT);
        assertThat(manifest.getLinesMatched()).isEqualTo(1);
        assertThat(manifest.getLinesUnmatched()).isZero();
        assertThat(manifest.getStatus()).isEqualTo(PriceCatalogImportStatus.COMPLETED);
    }

    @Test
    void leavesTheOriginalImportsCountersUntouched() {
        writer.commit(profile(), List.of(resolvedLine(1)), APPLIED_AT, 500);

        // The original records what the vendor sent and how much matched AT THE TIME. Only the new
        // manifest is written; the healed one is read and left alone.
        ArgumentCaptor<PriceCatalogImportEntity> captor = ArgumentCaptor.forClass(PriceCatalogImportEntity.class);
        verify(importRepository).save(captor.capture());
        assertThat(captor.getValue().getImportManifestId()).isNotEqualTo(ORIGIN_IMPORT);
    }

    @Test
    void carriesTheOriginalNormRatherThanInventingOne() {
        PriceCatalogImportEntity manifest = writer.commit(profile(), List.of(resolvedLine(1)), APPLIED_AT, 500);

        assertThat(manifest.getProtocolFamily()).isEqualTo(ProtocolFamily.EDIWHEEL_B);
        assertThat(manifest.getProtocolVersion()).isEqualTo("B4_0");
    }

    @Test
    void closesEveryLineItApplied() {
        writer.commit(profile(), List.of(resolvedLine(1), resolvedLine(2)), APPLIED_AT, 500);

        ArgumentCaptor<PriceCatalogUnmatchedLineEntity> captor =
                ArgumentCaptor.forClass(PriceCatalogUnmatchedLineEntity.class);
        verify(unmatchedLineRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(row -> {
            assertThat(row.getResolvedAt()).isEqualTo(APPLIED_AT);
            assertThat(row.getResolvedProductId()).isEqualTo(PRODUCT_ID);
        });
    }

    @Test
    void datesTheStagedEntryByTheVendorFetchNotByTheReapplication() {
        writer.commit(profile(), List.of(resolvedLine(1)), APPLIED_AT, 500);

        ArgumentCaptor<PriceCatalogEntryEntity> captor = ArgumentCaptor.forClass(PriceCatalogEntryEntity.class);
        verify(entryRepository).save(captor.capture());
        // Stamping "now" would let a re-applied old price outrank a newer fetch in the
        // latest-selection tie-break (ADR-0053 §2).
        assertThat(captor.getValue().getFetchedAt()).isEqualTo(VENDOR_FETCHED_AT);
        assertThat(captor.getValue().getImportManifestId()).isEqualTo(NEW_MANIFEST);
        assertThat(captor.getValue().getMatchedProductId()).isEqualTo(PRODUCT_ID);
    }

    @Test
    void publishesChunksAndACompletionEventSoAConsumerCanConfirmItWhole() {
        writer.commit(profile(), List.of(resolvedLine(1), resolvedLine(2), resolvedLine(3)), APPLIED_AT, 2);

        List<DomainEventEnvelope<?>> events = capturedEvents();
        assertThat(events).hasSize(3);
        assertThat(events.stream()
                        .filter(e -> SupplierPriceCatalogUpdatedV1.EVENT_TYPE.equals(e.eventType()))
                        .count())
                .isEqualTo(2);

        DomainEventEnvelope<?> completion = events.getLast();
        assertThat(completion.eventType()).isEqualTo(SupplierPriceCatalogImportCompletedV1.EVENT_TYPE);
        SupplierPriceCatalogImportCompletedV1 payload = (SupplierPriceCatalogImportCompletedV1) completion.payload();
        assertThat(payload.chunkCount()).isEqualTo(2);
        assertThat(payload.linesMatched()).isEqualTo(3);
    }

    @Test
    void keysEveryEventOnTheReapplicationManifestNotTheOriginal() {
        writer.commit(profile(), List.of(resolvedLine(1)), APPLIED_AT, 500);

        assertThat(capturedEvents())
                .allSatisfy(event -> assertThat(event.aggregateId()).isEqualTo(NEW_MANIFEST));
    }
}
