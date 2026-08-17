package com.positivity.supplier.internal.pricecatalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.supplier.SupplierPriceCatalogImportCompletedV1;
import com.positivity.domainevents.supplier.SupplierPriceCatalogUpdatedV1;
import com.positivity.supplier.internal.adapter.ediwheelb.PricatDocument;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierPriceCatalogEntry;
import com.positivity.supplier.internal.entity.PriceCatalogEntryEntity;
import com.positivity.supplier.internal.entity.PriceCatalogImportEntity;
import com.positivity.supplier.internal.entity.PriceCatalogUnmatchedLineEntity;
import com.positivity.supplier.internal.entity.SupplierAccountEntity;
import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.enums.PriceCatalogImportStatus;
import com.positivity.supplier.internal.enums.PriceCatalogMatchMethod;
import com.positivity.supplier.internal.enums.UnmatchedLineReason;
import com.positivity.supplier.internal.pricecatalog.service.PriceCatalogImporter.MatchedLine;
import com.positivity.supplier.internal.pricecatalog.service.PriceCatalogImporter.PreparedImport;
import com.positivity.supplier.internal.pricecatalog.service.PriceCatalogImporter.QuarantinedLine;
import com.positivity.supplier.internal.repository.PriceCatalogEntryRepository;
import com.positivity.supplier.internal.repository.PriceCatalogImportRepository;
import com.positivity.supplier.internal.repository.PriceCatalogUnmatchedLineRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PRICAT staging, chunking and events (#1224, ADR-0053 §7)")
class PriceCatalogStagingWriterTest {

    private static final UUID MANIFEST_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d");
    private static final Instant FETCHED_AT = Instant.parse("2026-08-13T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-13T09:05:00Z"), ZoneOffset.UTC);

    @Mock
    private PriceCatalogImportRepository importRepository;

    @Mock
    private PriceCatalogEntryRepository entryRepository;

    @Mock
    private PriceCatalogUnmatchedLineRepository unmatchedLineRepository;

    @Mock
    private SupplierOutboxEventWriter outboxWriter;

    private PriceCatalogStagingWriter writer;

    @BeforeEach
    void setUp() {
        writer = new PriceCatalogStagingWriter(
                importRepository, entryRepository, unmatchedLineRepository, outboxWriter, CLOCK);
        when(importRepository.save(any(PriceCatalogImportEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(entryRepository.save(any(PriceCatalogEntryEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(unmatchedLineRepository.save(any(PriceCatalogUnmatchedLineEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static ResolvedBinding binding() {
        SupplierProfileEntity profile = new SupplierProfileEntity();
        profile.setVendorProfileId(PROFILE_ID);
        profile.setSupplierRef("michelin-eu");
        profile.setEnabled(true);

        SupplierEndpointBindingEntity endpointBinding = new SupplierEndpointBindingEntity();
        endpointBinding.setId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5e"));
        endpointBinding.setVendorProfileId(PROFILE_ID);
        endpointBinding.setCapability(SupplierCapability.PRICE_CATALOG);
        endpointBinding.setProtocolFamily(ProtocolFamily.EDIWHEEL_B);
        endpointBinding.setProtocolVersion("B4_0");
        endpointBinding.setEnabled(true);

        return new ResolvedBinding(
                profile,
                endpointBinding,
                new SupplierAuthConfigEntity(),
                SupplierCapability.PRICE_CATALOG,
                ProtocolFamily.EDIWHEEL_B,
                ProtocolVersion.B4_0);
    }

    private static SupplierAccountEntity billing() {
        SupplierAccountEntity account = new SupplierAccountEntity();
        account.setAccountNumber("30012456");
        account.setAgencyCode("91");
        return account;
    }

    private static SupplierPriceCatalogEntry entry(int position) {
        return new SupplierPriceCatalogEntry(
                "352870999908" + position,
                "99990" + position,
                null,
                new BigDecimal("120.00"),
                new BigDecimal("100.00"),
                new BigDecimal("90.00"),
                new BigDecimal("25"),
                new BigDecimal("3.50"),
                LocalDate.of(2026, 2, 1),
                "SE",
                "SEK",
                "DOC-1",
                LocalDate.of(2026, 1, 30),
                position);
    }

    private static List<MatchedLine> matchedLines(int count) {
        List<MatchedLine> lines = new ArrayList<>(count);
        IntStream.rangeClosed(1, count)
                .forEach(i -> lines.add(new MatchedLine(entry(i), PRODUCT_ID, PriceCatalogMatchMethod.EAN)));
        return lines;
    }

    private static PricatDocument document(int linesFetched) {
        return new PricatDocument("DOC-1", LocalDate.of(2026, 1, 30), "SE", "SEK", List.of(), List.of(), linesFetched);
    }

    private PriceCatalogImportEntity commit(PreparedImport prepared, int linesFetched, int chunkSize) {
        return writer.commit(
                prepared, document(linesFetched), binding(), billing(), MANIFEST_ID, "corr-1", FETCHED_AT, chunkSize);
    }

    @SuppressWarnings("unchecked")
    private List<DomainEventEnvelope<?>> capturedEvents() {
        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(outboxWriter, org.mockito.Mockito.atLeastOnce()).publish(eq("supplier.events.v1"), captor.capture());
        return captor.getAllValues();
    }

    @Nested
    @DisplayName("chunking")
    class Chunking {

        @Test
        void splitsLinesIntoChunksOfTheConfiguredSize() {
            commit(new PreparedImport(matchedLines(1100), List.of(), 0), 1100, 500);

            List<DomainEventEnvelope<?>> events = capturedEvents();
            List<DomainEventEnvelope<?>> chunks = events.stream()
                    .filter(e -> SupplierPriceCatalogUpdatedV1.EVENT_TYPE.equals(e.eventType()))
                    .toList();
            assertThat(chunks).hasSize(3);
            assertThat(((SupplierPriceCatalogUpdatedV1) chunks.get(0).payload()).lines())
                    .hasSize(500);
            assertThat(((SupplierPriceCatalogUpdatedV1) chunks.get(2).payload()).lines())
                    .hasSize(100);
            assertThat(((SupplierPriceCatalogUpdatedV1) chunks.get(2).payload()).chunkSequence())
                    .isEqualTo(3);
            assertThat(((SupplierPriceCatalogUpdatedV1) chunks.get(2).payload()).chunkCount())
                    .isEqualTo(3);
        }

        @Test
        void stagesEachLineWithTheChunkItWasPublishedIn() {
            commit(new PreparedImport(matchedLines(5), List.of(), 0), 5, 2);

            // Recorded, not recomputed. A later re-emit has to reproduce these boundaries exactly:
            // the consumer skips chunk sequences it already applied, so a line that moved across a
            // boundary would be dropped permanently rather than re-delivered (ADR-0044 §4).
            ArgumentCaptor<PriceCatalogEntryEntity> captor = ArgumentCaptor.forClass(PriceCatalogEntryEntity.class);
            verify(entryRepository, org.mockito.Mockito.times(5)).save(captor.capture());
            assertThat(captor.getAllValues())
                    .extracting(PriceCatalogEntryEntity::getChunkSequence)
                    .containsExactly(1, 1, 2, 2, 3);
        }

        @Test
        void producesNoTrailingEmptyChunkOnAnExactMultiple() {
            commit(new PreparedImport(matchedLines(1000), List.of(), 0), 1000, 500);

            assertThat(capturedEvents().stream()
                            .filter(e -> SupplierPriceCatalogUpdatedV1.EVENT_TYPE.equals(e.eventType()))
                            .count())
                    .isEqualTo(2);
        }

        @Test
        void publishesOneChunkForASingleLine() {
            commit(new PreparedImport(matchedLines(1), List.of(), 0), 1, 500);

            assertThat(capturedEvents().stream()
                            .filter(e -> SupplierPriceCatalogUpdatedV1.EVENT_TYPE.equals(e.eventType()))
                            .count())
                    .isEqualTo(1);
        }

        @Test
        void treatsANonPositiveChunkSizeAsOneLinePerChunkRatherThanLoopingForever() {
            commit(new PreparedImport(matchedLines(3), List.of(), 0), 3, 0);

            assertThat(capturedEvents().stream()
                            .filter(e -> SupplierPriceCatalogUpdatedV1.EVENT_TYPE.equals(e.eventType()))
                            .count())
                    .isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("events")
    class Events {

        @Test
        void sequencesTheCompletionEventAfterEveryChunk() {
            commit(new PreparedImport(matchedLines(3), List.of(), 0), 3, 2);

            List<DomainEventEnvelope<?>> events = capturedEvents();
            DomainEventEnvelope<?> completion = events.getLast();
            assertThat(completion.eventType()).isEqualTo(SupplierPriceCatalogImportCompletedV1.EVENT_TYPE);
            assertThat(completion.aggregateVersion())
                    .isGreaterThan(events.get(events.size() - 2).aggregateVersion());
        }

        @Test
        void keysEveryEventOnTheImportManifest() {
            commit(new PreparedImport(matchedLines(3), List.of(), 0), 3, 2);

            assertThat(capturedEvents())
                    .allSatisfy(event -> assertThat(event.aggregateId()).isEqualTo(MANIFEST_ID));
        }

        @Test
        void reportsCountersThatPartitionTheFetchOnTheCompletionEvent() {
            QuarantinedLine quarantined = new QuarantinedLine(
                    entry(9), 9, "3528709999099", "999909", null, UnmatchedLineReason.NO_CATALOG_MATCH, "no product");

            commit(new PreparedImport(matchedLines(2), List.of(quarantined), 0), 3, 500);

            SupplierPriceCatalogImportCompletedV1 completion = (SupplierPriceCatalogImportCompletedV1)
                    capturedEvents().getLast().payload();
            assertThat(completion.linesFetched()).isEqualTo(3);
            assertThat(completion.linesMatched()).isEqualTo(2);
            assertThat(completion.linesUnmatched()).isEqualTo(1);
            assertThat(completion.linesDuplicate()).isZero();
            assertThat(completion.status()).isEqualTo(PriceCatalogImportStatus.COMPLETED.name());
            assertThat(completion.contentChecksum()).isNotBlank();
        }

        @Test
        void publishesOnlyMatchedLinesInChunks() {
            QuarantinedLine quarantined = new QuarantinedLine(
                    entry(9), 9, "3528709999099", "999909", null, UnmatchedLineReason.NO_CATALOG_MATCH, "no product");

            commit(new PreparedImport(matchedLines(1), List.of(quarantined), 0), 2, 500);

            SupplierPriceCatalogUpdatedV1 chunk =
                    (SupplierPriceCatalogUpdatedV1) capturedEvents().getFirst().payload();
            assertThat(chunk.lines()).singleElement().satisfies(line -> {
                assertThat(line.productId()).isEqualTo(PRODUCT_ID);
                assertThat(line.matchMethod()).isEqualTo("EAN");
                assertThat(line.netPrice()).isEqualByComparingTo(new BigDecimal("90.00"));
                assertThat(line.effectiveFrom()).isEqualTo(LocalDate.of(2026, 2, 1));
            });
        }

        @Test
        void marksAZeroLineDocumentEmptyAndStillCompletesIt() {
            PriceCatalogImportEntity row = commit(new PreparedImport(List.of(), List.of(), 0), 0, 500);

            assertThat(row.getStatus()).isEqualTo(PriceCatalogImportStatus.EMPTY);
            List<DomainEventEnvelope<?>> events = capturedEvents();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().eventType()).isEqualTo(SupplierPriceCatalogImportCompletedV1.EVENT_TYPE);
        }
    }

    @Nested
    @DisplayName("integrity")
    class Integrity {

        @Test
        void failsWhenTheCountersDoNotPartitionTheFetch() {
            assertThatThrownBy(() -> commit(new PreparedImport(matchedLines(2), List.of(), 0), 5, 500))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("line accounting mismatch");
        }

        @Test
        void stagesOneRowPerMatchedLineAndOnePerQuarantinedLine() {
            QuarantinedLine quarantined = new QuarantinedLine(
                    entry(9), 9, "3528709999099", "999909", null, UnmatchedLineReason.NO_CATALOG_MATCH, "no product");

            commit(new PreparedImport(matchedLines(2), List.of(quarantined), 0), 3, 500);

            verify(entryRepository, org.mockito.Mockito.times(2)).save(any(PriceCatalogEntryEntity.class));
            verify(unmatchedLineRepository).save(any(PriceCatalogUnmatchedLineEntity.class));
        }
    }

    @Nested
    @DisplayName("failure path")
    class Failures {

        @Test
        void recordsAFailedImportWithoutStagingOrPublishingAnything() {
            PriceCatalogImportEntity row = writer.persistFailure(
                    MANIFEST_ID, binding(), billing(), FETCHED_AT, "corr-1", "vendor exchange failed: TIMEOUT");

            assertThat(row.getStatus()).isEqualTo(PriceCatalogImportStatus.FAILED);
            assertThat(row.getFailureDetail()).contains("TIMEOUT");
            verify(entryRepository, never()).save(any(PriceCatalogEntryEntity.class));
            verify(unmatchedLineRepository, never()).save(any(PriceCatalogUnmatchedLineEntity.class));
            verify(outboxWriter, never()).publish(any(), any());
        }

        @Test
        void truncatesAnOverlongFailureDetailToItsColumnWidth() {
            String longDetail = "x".repeat(5000);

            PriceCatalogImportEntity row =
                    writer.persistFailure(MANIFEST_ID, binding(), billing(), FETCHED_AT, "corr-1", longDetail);

            assertThat(row.getFailureDetail()).hasSize(2000);
        }
    }
}
