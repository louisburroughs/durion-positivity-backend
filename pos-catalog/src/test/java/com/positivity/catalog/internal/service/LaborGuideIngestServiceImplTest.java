package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.dto.LaborGuideImportSummaryDto;
import com.positivity.catalog.internal.entity.LaborGuideImportEntity;
import com.positivity.catalog.internal.entity.LaborGuideUnmappedOperationEntity;
import com.positivity.catalog.internal.entity.ServiceLaborStandardEntity;
import com.positivity.catalog.internal.entity.ServiceOperationXrefEntity;
import com.positivity.catalog.internal.enums.LaborTimeType;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.repository.LaborGuideImportChunkRepository;
import com.positivity.catalog.internal.repository.LaborGuideImportRepository;
import com.positivity.catalog.internal.repository.LaborGuideUnmappedOperationRepository;
import com.positivity.catalog.internal.repository.ServiceLaborStandardRepository;
import com.positivity.catalog.internal.repository.ServiceOperationXrefRepository;
import com.positivity.catalog.internal.spi.LaborTimeProviderPort;
import com.positivity.catalog.internal.spi.ProviderCallException;
import com.positivity.catalog.internal.spi.model.LaborTimeProviderDescriptor;
import com.positivity.catalog.internal.spi.model.LaborTimeProviderDescriptor.LicenseMode;
import com.positivity.catalog.internal.spi.model.ProviderFeedChunk;
import com.positivity.catalog.internal.spi.model.ProviderFeedLine;
import com.positivity.catalog.internal.spi.model.ProviderFeedRevision;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Chunked-manifest import rules (#1569 Phase 1, sourcing plan §5.3): idempotent chunks,
 * skip-unchanged / supersede-changed lines, unmapped codes queue and never block, completeness
 * is counted, and a provider failure mid-run leaves a resumable APPLYING row.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LaborGuideIngestServiceImpl")
class LaborGuideIngestServiceImplTest {

    private static final UUID MANIFEST_ID = UUID.fromString("7f1e6b2a-4c5d-4e8f-9a0b-1c2d3e4f5a6b");
    private static final UUID SERVICE_ID = UUID.fromString("56b14899-cb6c-7628-0763-4c603ec0a325");

    @Mock
    private LaborTimeProviderPort port;

    @Mock
    private LaborGuideImportRepository importRepository;

    @Mock
    private LaborGuideImportChunkRepository chunkRepository;

    @Mock
    private LaborGuideUnmappedOperationRepository unmappedRepository;

    @Mock
    private ServiceOperationXrefRepository xrefRepository;

    @Mock
    private ServiceLaborStandardRepository standardRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private LaborGuideIngestServiceImpl service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(port.descriptor())
                .thenReturn(new LaborTimeProviderDescriptor("MOCKGUIDE", "Mock guide", LicenseMode.STORE, 100));
        service = new LaborGuideIngestServiceImpl(
                Map.of("MOCKGUIDE", port),
                importRepository,
                chunkRepository,
                unmappedRepository,
                xrefRepository,
                standardRepository,
                transactionManager,
                Clock.fixed(Instant.parse("2026-09-02T08:00:00Z"), ZoneOffset.UTC));

        when(importRepository.findById(any())).thenReturn(Optional.empty());
        when(importRepository.findFirstBySourceCodeAndStatusOrderByCompletedAtDesc(any(), any()))
                .thenReturn(Optional.empty());
        when(importRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(chunkRepository.existsByImportManifestIdAndChunkSequence(any(), anyInt()))
                .thenReturn(false);
        when(chunkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(unmappedRepository.findBySourceCodeAndProviderOpCode(any(), any())).thenReturn(Optional.empty());
        when(unmappedRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(xrefRepository.findBySourceCodeAndProviderOpCode(any(), any())).thenReturn(Optional.empty());
        when(standardRepository.findByServiceIdAndSourceCodeAndSupersededAtIsNull(any(), any()))
                .thenReturn(List.of());
        when(standardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(standardRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static ProviderFeedRevision manifest(int chunks, long lines) {
        return new ProviderFeedRevision(MANIFEST_ID, "2026-09-01", chunks, lines, "checksum");
    }

    private static ProviderFeedLine line(String code, BigDecimal hours) {
        return new ProviderFeedLine(
                code, "2019-2023", "Honda", "Civic", null, null, hours, "RETAIL_FLAT_RATE", null, List.of(), null);
    }

    private ServiceOperationXrefEntity xref() {
        ServiceOperationXrefEntity x = new ServiceOperationXrefEntity();
        x.setServiceId(SERVICE_ID);
        x.setSourceCode("MOCKGUIDE");
        x.setProviderOpCode("MG-BRAKE-PAD-FRONT");
        return x;
    }

    @Nested
    @DisplayName("preconditions")
    class Preconditions {

        @Test
        @DisplayName("unknown source is 404 — nothing to import from")
        void unknownSource() {
            assertThatThrownBy(() -> service.runImport("NOPE")).isInstanceOf(CatalogNotFoundException.class);
        }

        @Test
        @DisplayName("QUERY_ONLY source refuses to import — its license forbids persistence")
        void queryOnlySourceRefused() {
            when(port.descriptor())
                    .thenReturn(
                            new LaborTimeProviderDescriptor("MOCKGUIDE", "Mock guide", LicenseMode.QUERY_ONLY, 100));

            assertThatThrownBy(() -> service.runImport("MOCKGUIDE"))
                    .isInstanceOf(CatalogBusinessRuleException.class)
                    .hasMessageContaining("QUERY_ONLY");
        }

        @Test
        @DisplayName("unreachable vendor before the import starts is a 409, not a stack trace")
        void unreachableVendor() {
            when(port.openFeedRevision(any())).thenThrow(new ProviderCallException("down"));

            assertThatThrownBy(() -> service.runImport("MOCKGUIDE"))
                    .isInstanceOf(CatalogBusinessRuleException.class)
                    .hasMessageContaining("unreachable");
        }

        @Test
        @DisplayName("an already-COMPLETE revision is a recorded no-op")
        void alreadyComplete() {
            when(port.openFeedRevision(any())).thenReturn(manifest(1, 1));
            LaborGuideImportEntity done = new LaborGuideImportEntity();
            done.setImportManifestId(MANIFEST_ID);
            done.setSourceCode("MOCKGUIDE");
            done.setSourceRevision("2026-09-01");
            done.setStatus(LaborGuideImportEntity.Status.COMPLETE);
            when(importRepository.findById(MANIFEST_ID)).thenReturn(Optional.of(done));

            LaborGuideImportSummaryDto summary = service.runImport("MOCKGUIDE");

            assertThat(summary.isAlreadyImported()).isTrue();
            verify(port, never()).fetchFeedChunk(any(), anyInt());
        }
    }

    @Nested
    @DisplayName("line application")
    class LineApplication {

        @Test
        @DisplayName("a mapped new line writes a standard with feed provenance; counts reconcile to COMPLETE")
        void mappedNewLineWrites() {
            when(port.openFeedRevision(any())).thenReturn(manifest(1, 1));
            when(port.fetchFeedChunk(MANIFEST_ID, 1))
                    .thenReturn(new ProviderFeedChunk(
                            MANIFEST_ID, 1, List.of(line("MG-BRAKE-PAD-FRONT", new BigDecimal("1.5")))));
            when(xrefRepository.findBySourceCodeAndProviderOpCode("MOCKGUIDE", "MG-BRAKE-PAD-FRONT"))
                    .thenReturn(Optional.of(xref()));

            LaborGuideImportSummaryDto summary = service.runImport("MOCKGUIDE");

            assertThat(summary.getStatus()).isEqualTo("COMPLETE");
            assertThat(summary.getStandardsWritten()).isEqualTo(1);
            ArgumentCaptor<ServiceLaborStandardEntity> saved =
                    ArgumentCaptor.forClass(ServiceLaborStandardEntity.class);
            verify(standardRepository).save(saved.capture());
            assertThat(saved.getValue().getSourceCode()).isEqualTo("MOCKGUIDE");
            assertThat(saved.getValue().getSourceRevision()).isEqualTo("2026-09-01");
            assertThat(saved.getValue().getImportManifestId()).isEqualTo(MANIFEST_ID);
            assertThat(saved.getValue().getServiceId()).isEqualTo(SERVICE_ID);
        }

        @Test
        @DisplayName("an unmapped code queues for curation and the import stays COMPLETE")
        void unmappedLineQueues() {
            when(port.openFeedRevision(any())).thenReturn(manifest(1, 1));
            when(port.fetchFeedChunk(MANIFEST_ID, 1))
                    .thenReturn(new ProviderFeedChunk(
                            MANIFEST_ID, 1, List.of(line("MG-FOG-LAMP-ALIGN", new BigDecimal("0.5")))));

            LaborGuideImportSummaryDto summary = service.runImport("MOCKGUIDE");

            assertThat(summary.getStatus()).isEqualTo("COMPLETE");
            assertThat(summary.getLinesUnmapped()).isEqualTo(1);
            ArgumentCaptor<LaborGuideUnmappedOperationEntity> queued =
                    ArgumentCaptor.forClass(LaborGuideUnmappedOperationEntity.class);
            verify(unmappedRepository).save(queued.capture());
            assertThat(queued.getValue().getProviderOpCode()).isEqualTo("MG-FOG-LAMP-ALIGN");
            verify(standardRepository, never()).save(any());
        }

        @Test
        @DisplayName("an unchanged line is a skip, not a supersession")
        void unchangedLineSkips() {
            when(port.openFeedRevision(any())).thenReturn(manifest(1, 1));
            when(port.fetchFeedChunk(MANIFEST_ID, 1))
                    .thenReturn(new ProviderFeedChunk(
                            MANIFEST_ID, 1, List.of(line("MG-BRAKE-PAD-FRONT", new BigDecimal("1.5")))));
            when(xrefRepository.findBySourceCodeAndProviderOpCode("MOCKGUIDE", "MG-BRAKE-PAD-FRONT"))
                    .thenReturn(Optional.of(xref()));
            ServiceLaborStandardEntity active = activeRow(new BigDecimal("1.5"));
            when(standardRepository.findByServiceIdAndSourceCodeAndSupersededAtIsNull(SERVICE_ID, "MOCKGUIDE"))
                    .thenReturn(List.of(active));

            LaborGuideImportSummaryDto summary = service.runImport("MOCKGUIDE");

            assertThat(summary.getLinesUnchanged()).isEqualTo(1);
            assertThat(active.getSupersededAt()).isNull();
            verify(standardRepository, never()).save(any());
        }

        @Test
        @DisplayName("a changed line supersedes the active row (flushed first) and inserts the new value")
        void changedLineSupersedes() {
            when(port.openFeedRevision(any())).thenReturn(manifest(1, 1));
            when(port.fetchFeedChunk(MANIFEST_ID, 1))
                    .thenReturn(new ProviderFeedChunk(
                            MANIFEST_ID, 1, List.of(line("MG-BRAKE-PAD-FRONT", new BigDecimal("1.8")))));
            when(xrefRepository.findBySourceCodeAndProviderOpCode("MOCKGUIDE", "MG-BRAKE-PAD-FRONT"))
                    .thenReturn(Optional.of(xref()));
            ServiceLaborStandardEntity active = activeRow(new BigDecimal("1.5"));
            when(standardRepository.findByServiceIdAndSourceCodeAndSupersededAtIsNull(SERVICE_ID, "MOCKGUIDE"))
                    .thenReturn(List.of(active));

            LaborGuideImportSummaryDto summary = service.runImport("MOCKGUIDE");

            assertThat(summary.getStandardsWritten()).isEqualTo(1);
            assertThat(active.getSupersededAt()).isNotNull();
            org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(standardRepository);
            inOrder.verify(standardRepository).saveAndFlush(active);
            inOrder.verify(standardRepository).save(any());
        }

        @Test
        @DisplayName("a time class we do not model queues under a synthetic code instead of failing the import")
        void unknownTimeTypeQueues() {
            when(port.openFeedRevision(any())).thenReturn(manifest(1, 1));
            ProviderFeedLine weird = new ProviderFeedLine(
                    "MG-BRAKE-PAD-FRONT",
                    null,
                    null,
                    null,
                    null,
                    null,
                    new BigDecimal("1.0"),
                    "FLEET_CONTRACT",
                    null,
                    List.of(),
                    null);
            when(port.fetchFeedChunk(MANIFEST_ID, 1)).thenReturn(new ProviderFeedChunk(MANIFEST_ID, 1, List.of(weird)));
            when(xrefRepository.findBySourceCodeAndProviderOpCode("MOCKGUIDE", "MG-BRAKE-PAD-FRONT"))
                    .thenReturn(Optional.of(xref()));

            LaborGuideImportSummaryDto summary = service.runImport("MOCKGUIDE");

            assertThat(summary.getLinesUnmapped()).isEqualTo(1);
            ArgumentCaptor<LaborGuideUnmappedOperationEntity> queued =
                    ArgumentCaptor.forClass(LaborGuideUnmappedOperationEntity.class);
            verify(unmappedRepository).save(queued.capture());
            assertThat(queued.getValue().getProviderOpCode()).contains("#FLEET_CONTRACT");
        }

        private ServiceLaborStandardEntity activeRow(BigDecimal hours) {
            ServiceLaborStandardEntity row = new ServiceLaborStandardEntity();
            row.setId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9d01"));
            row.setServiceId(SERVICE_ID);
            row.setVehicleYear("2019-2023");
            row.setMake("Honda");
            row.setModel("Civic");
            row.setLaborHours(hours);
            row.setTimeType(LaborTimeType.RETAIL_FLAT_RATE);
            row.setSourceCode("MOCKGUIDE");
            row.setSourceRevision("2026-08-01");
            return row;
        }
    }

    @Nested
    @DisplayName("resume and failure")
    class ResumeAndFailure {

        @Test
        @DisplayName("already-applied chunks are skipped — re-delivery is a no-op")
        void appliedChunksSkip() {
            when(port.openFeedRevision(any())).thenReturn(manifest(2, 2));
            when(chunkRepository.existsByImportManifestIdAndChunkSequence(MANIFEST_ID, 1))
                    .thenReturn(true);
            when(port.fetchFeedChunk(MANIFEST_ID, 2))
                    .thenReturn(new ProviderFeedChunk(
                            MANIFEST_ID, 2, List.of(line("MG-FOG-LAMP-ALIGN", new BigDecimal("0.5")))));

            service.runImport("MOCKGUIDE");

            verify(port, never()).fetchFeedChunk(MANIFEST_ID, 1);
            verify(port).fetchFeedChunk(MANIFEST_ID, 2);
        }

        @Test
        @DisplayName("a provider failure mid-import leaves a resumable APPLYING row, never throws")
        void midImportFailureLeavesApplying() {
            when(port.openFeedRevision(any())).thenReturn(manifest(2, 2));
            when(port.fetchFeedChunk(MANIFEST_ID, 1))
                    .thenReturn(new ProviderFeedChunk(
                            MANIFEST_ID, 1, List.of(line("MG-FOG-LAMP-ALIGN", new BigDecimal("0.5")))));
            when(port.fetchFeedChunk(MANIFEST_ID, 2)).thenThrow(new ProviderCallException("gone"));

            LaborGuideImportSummaryDto summary = service.runImport("MOCKGUIDE");

            assertThat(summary.getStatus()).isEqualTo("APPLYING");
            assertThat(summary.getChunksApplied()).isEqualTo(1);
        }

        @Test
        @DisplayName("a chunk answering with the wrong manifest id bails resumable — nothing applied under it")
        void wrongManifestIdBailsResumable() {
            when(port.openFeedRevision(any())).thenReturn(manifest(1, 1));
            UUID otherManifest = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9e01");
            when(port.fetchFeedChunk(MANIFEST_ID, 1))
                    .thenReturn(new ProviderFeedChunk(
                            otherManifest, 1, List.of(line("MG-BRAKE-PAD-FRONT", new BigDecimal("1.5")))));
            when(xrefRepository.findBySourceCodeAndProviderOpCode("MOCKGUIDE", "MG-BRAKE-PAD-FRONT"))
                    .thenReturn(Optional.of(xref()));

            LaborGuideImportSummaryDto summary = service.runImport("MOCKGUIDE");

            // Exactly like a failed fetch: the APPLYING row stays resumable and no standard or
            // chunk bookkeeping lands for the impostor chunk.
            assertThat(summary.getStatus()).isEqualTo("APPLYING");
            assertThat(summary.getChunksApplied()).isZero();
            assertThat(summary.getStandardsWritten()).isZero();
            verify(standardRepository, never()).save(any());
            verify(chunkRepository, never()).save(any());
        }

        @Test
        @DisplayName("a chunk answering with the wrong sequence bails resumable — nothing applied under it")
        void wrongChunkSequenceBailsResumable() {
            when(port.openFeedRevision(any())).thenReturn(manifest(2, 2));
            when(port.fetchFeedChunk(MANIFEST_ID, 1))
                    .thenReturn(new ProviderFeedChunk(
                            MANIFEST_ID, 2, List.of(line("MG-BRAKE-PAD-FRONT", new BigDecimal("1.5")))));
            when(xrefRepository.findBySourceCodeAndProviderOpCode("MOCKGUIDE", "MG-BRAKE-PAD-FRONT"))
                    .thenReturn(Optional.of(xref()));

            LaborGuideImportSummaryDto summary = service.runImport("MOCKGUIDE");

            assertThat(summary.getStatus()).isEqualTo("APPLYING");
            assertThat(summary.getChunksApplied()).isZero();
            verify(standardRepository, never()).save(any());
            verify(chunkRepository, never()).save(any());
            // The loop stops at the mismatch rather than marching on to chunk 2.
            verify(port, never()).fetchFeedChunk(MANIFEST_ID, 2);
        }

        @Test
        @DisplayName("counts that do not reconcile close the import INCOMPLETE, never silently complete")
        void mismatchedCountsIncomplete() {
            // Manifest promises 2 lines but the single chunk carries 1.
            when(port.openFeedRevision(any())).thenReturn(manifest(1, 2));
            when(port.fetchFeedChunk(MANIFEST_ID, 1))
                    .thenReturn(new ProviderFeedChunk(
                            MANIFEST_ID, 1, List.of(line("MG-FOG-LAMP-ALIGN", new BigDecimal("0.5")))));

            LaborGuideImportSummaryDto summary = service.runImport("MOCKGUIDE");

            assertThat(summary.getStatus()).isEqualTo("INCOMPLETE");
        }
    }
}
