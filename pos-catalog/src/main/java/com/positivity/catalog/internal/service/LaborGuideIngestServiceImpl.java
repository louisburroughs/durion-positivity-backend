package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.LaborGuideImportSummaryDto;
import com.positivity.catalog.internal.entity.LaborGuideImportChunkEntity;
import com.positivity.catalog.internal.entity.LaborGuideImportEntity;
import com.positivity.catalog.internal.entity.LaborGuideUnmappedOperationEntity;
import com.positivity.catalog.internal.entity.ServiceLaborStandardEntity;
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
import com.positivity.catalog.internal.spi.model.LaborTimeProviderDescriptor.LicenseMode;
import com.positivity.catalog.internal.spi.model.ProviderFeedChunk;
import com.positivity.catalog.internal.spi.model.ProviderFeedLine;
import com.positivity.catalog.internal.spi.model.ProviderFeedRevision;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Chunked-manifest labor-guide import (#1569 Phase 1, sourcing plan §5.3).
 *
 * <h2>Idempotency, two guards</h2>
 *
 * The manifest id is provider-assigned, so a re-run of an already-COMPLETE revision returns its
 * recorded summary without touching the provider again; and each chunk's
 * {@code (manifest, sequence)} row makes re-delivery and resume no-ops — the same two-guard
 * scheme the supplier-price listener uses.
 *
 * <h2>Transaction shape</h2>
 *
 * Each chunk applies in its own transaction (a {@link TransactionTemplate}, because the loop
 * calls the provider between transactions and holding one open across a vendor HTTP call would
 * pin a connection to vendor latency). A crash mid-import leaves an APPLYING row that the next
 * run resumes from the first missing chunk.
 *
 * <h2>Completeness is counted</h2>
 *
 * COMPLETE requires every expected chunk applied and the reconciled line count to match the
 * manifest. The vendor checksum is recorded for audit; recomputing it would require
 * bit-identical canonical serialization on both sides, which the counts already guard well
 * enough at this phase (noted in the plan's Phase 2 scale pass).
 */
@Slf4j
@Service
public class LaborGuideIngestServiceImpl implements LaborGuideIngestService {

    private final Map<String, LaborTimeProviderPort> laborTimeProviders;
    private final LaborGuideImportRepository importRepository;
    private final LaborGuideImportChunkRepository chunkRepository;
    private final LaborGuideUnmappedOperationRepository unmappedRepository;
    private final ServiceOperationXrefRepository xrefRepository;
    private final ServiceLaborStandardRepository standardRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public LaborGuideIngestServiceImpl(
            Map<String, LaborTimeProviderPort> laborTimeProviders,
            LaborGuideImportRepository importRepository,
            LaborGuideImportChunkRepository chunkRepository,
            LaborGuideUnmappedOperationRepository unmappedRepository,
            ServiceOperationXrefRepository xrefRepository,
            ServiceLaborStandardRepository standardRepository,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.laborTimeProviders = laborTimeProviders;
        this.importRepository = importRepository;
        this.chunkRepository = chunkRepository;
        this.unmappedRepository = unmappedRepository;
        this.xrefRepository = xrefRepository;
        this.standardRepository = standardRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    @NonNull
    public LaborGuideImportSummaryDto runImport(@NonNull String sourceCode) {
        String normalized = sourceCode.trim().toUpperCase(Locale.ROOT);
        LaborTimeProviderPort port = laborTimeProviders.get(normalized);
        if (port == null) {
            throw new CatalogNotFoundException("No labor-guide provider configured for source " + normalized);
        }
        if (port.descriptor().licenseMode() != LicenseMode.STORE) {
            throw new CatalogBusinessRuleException("Source " + normalized
                    + " is licensed QUERY_ONLY; its times are consulted live and may not be imported");
        }

        String lastCompleted = importRepository
                .findFirstBySourceCodeAndStatusOrderByCompletedAtDesc(
                        normalized, LaborGuideImportEntity.Status.COMPLETE)
                .map(LaborGuideImportEntity::getSourceRevision)
                .orElse(null);

        ProviderFeedRevision manifest;
        try {
            manifest = port.openFeedRevision(lastCompleted);
        } catch (ProviderCallException e) {
            throw new CatalogBusinessRuleException(
                    "Labor-guide source " + normalized + " is unreachable; import not started");
        }

        Optional<LaborGuideImportEntity> existing = importRepository.findById(manifest.importManifestId());
        if (existing.isPresent() && existing.get().getStatus() == LaborGuideImportEntity.Status.COMPLETE) {
            LaborGuideImportSummaryDto summary = toSummary(existing.get(), 0, 0);
            summary.setAlreadyImported(true);
            return summary;
        }

        LaborGuideImportEntity importRow = existing.orElseGet(() -> newImportRow(normalized, manifest));

        long standardsWritten = 0;
        long linesUnchanged = 0;
        for (int seq = 1; seq <= manifest.expectedChunkCount(); seq++) {
            if (chunkRepository.existsByImportManifestIdAndChunkSequence(manifest.importManifestId(), seq)) {
                continue;
            }
            ProviderFeedChunk chunk;
            try {
                chunk = port.fetchFeedChunk(manifest.importManifestId(), seq);
            } catch (ProviderCallException e) {
                // Bail out mid-import: what already applied stays applied, the APPLYING row
                // remains, and the next run resumes right here.
                log.warn("Labor-guide import {} interrupted at chunk {}: {}", normalized, seq, e.getMessage());
                return toSummary(persistProgress(importRow, false), standardsWritten, linesUnchanged);
            }
            if (!manifest.importManifestId().equals(chunk.importManifestId()) || chunk.chunkSequence() != seq) {
                // A vendor answering with the wrong chunk identity (revision rolled mid-import,
                // or a broken sequence handler) must not be applied under this manifest's
                // bookkeeping — bail out resumable, exactly like a failed fetch.
                log.warn(
                        "Labor-guide import {} chunk identity mismatch: asked ({}, {}), got ({}, {})",
                        normalized,
                        manifest.importManifestId(),
                        seq,
                        chunk.importManifestId(),
                        chunk.chunkSequence());
                return toSummary(persistProgress(importRow, false), standardsWritten, linesUnchanged);
            }
            ChunkOutcome outcome = applyChunkTransactionally(normalized, importRow, chunk);
            standardsWritten += outcome.written();
            linesUnchanged += outcome.unchanged();
        }

        return toSummary(persistProgress(importRow, true), standardsWritten, linesUnchanged);
    }

    private LaborGuideImportEntity newImportRow(String sourceCode, ProviderFeedRevision manifest) {
        LaborGuideImportEntity row = new LaborGuideImportEntity();
        row.setImportManifestId(manifest.importManifestId());
        row.setSourceCode(sourceCode);
        row.setSourceRevision(manifest.sourceRevision());
        row.setExpectedChunkCount(manifest.expectedChunkCount());
        row.setExpectedLineCount(manifest.expectedLineCount());
        row.setContentChecksum(manifest.contentChecksum());
        row.setStatus(LaborGuideImportEntity.Status.APPLYING);
        return importRepository.save(row);
    }

    private record ChunkOutcome(long written, long unchanged) {}

    private ChunkOutcome applyChunkTransactionally(
            String sourceCode, LaborGuideImportEntity importRow, ProviderFeedChunk chunk) {
        return transactionTemplate.execute(status -> {
            long written = 0;
            long unchanged = 0;
            long unmapped = 0;
            for (ProviderFeedLine line : chunk.lines()) {
                LineResult result = applyLine(sourceCode, importRow, line);
                switch (result) {
                    case WRITTEN -> written++;
                    case UNCHANGED -> unchanged++;
                    case UNMAPPED -> unmapped++;
                }
            }
            LaborGuideImportChunkEntity chunkRow = new LaborGuideImportChunkEntity();
            chunkRow.setImportManifestId(chunk.importManifestId());
            chunkRow.setChunkSequence(chunk.chunkSequence());
            chunkRow.setLineCount(chunk.lines().size());
            chunkRow.setAppliedAt(Instant.now(clock));
            chunkRepository.save(chunkRow);

            importRow.setChunksApplied(importRow.getChunksApplied() + 1);
            importRow.setLinesApplied(
                    importRow.getLinesApplied() + chunk.lines().size());
            importRow.setLinesUnmapped(importRow.getLinesUnmapped() + unmapped);
            importRepository.save(importRow);
            return new ChunkOutcome(written, unchanged);
        });
    }

    private enum LineResult {
        WRITTEN,
        UNCHANGED,
        UNMAPPED
    }

    private LineResult applyLine(String sourceCode, LaborGuideImportEntity importRow, ProviderFeedLine line) {
        var xref = xrefRepository.findBySourceCodeAndProviderOpCode(sourceCode, line.providerOperationCode());
        if (xref.isEmpty()) {
            recordUnmapped(sourceCode, importRow, line.providerOperationCode());
            return LineResult.UNMAPPED;
        }
        LaborTimeType timeType;
        try {
            timeType = LaborTimeType.valueOf(line.timeType().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // A time class we do not model is indistinguishable from an unmapped operation for
            // curation purposes: queue it under a synthetic code so it is visible, skip the line.
            recordUnmapped(sourceCode, importRow, line.providerOperationCode() + "#" + line.timeType());
            return LineResult.UNMAPPED;
        }

        var serviceId = xref.get().getServiceId();
        Optional<ServiceLaborStandardEntity> active =
                standardRepository.findByServiceIdAndSourceCodeAndSupersededAtIsNull(serviceId, sourceCode).stream()
                        .filter(row -> row.getTimeType() == timeType
                                && Objects.equals(row.getVehicleYear(), line.vehicleYear())
                                && Objects.equals(row.getMake(), line.make())
                                && Objects.equals(row.getModel(), line.model())
                                && Objects.equals(row.getSubmodel(), line.submodel())
                                && Objects.equals(row.getEngineCode(), line.engineCode()))
                        .findFirst();

        if (active.isPresent() && sameContent(active.get(), line)) {
            return LineResult.UNCHANGED;
        }
        if (active.isPresent()) {
            // Supersede-not-update, flushed before the replacement lands so the V18 active-key
            // unique index sees the old row retired first (inserts flush ahead of updates).
            active.get().setSupersededAt(Instant.now(clock));
            standardRepository.saveAndFlush(active.get());
        }
        ServiceLaborStandardEntity replacement = new ServiceLaborStandardEntity();
        replacement.setServiceId(serviceId);
        replacement.setVehicleYear(line.vehicleYear());
        replacement.setMake(line.make());
        replacement.setModel(line.model());
        replacement.setSubmodel(line.submodel());
        replacement.setEngineCode(line.engineCode());
        replacement.setLaborHours(line.hours());
        replacement.setTimeType(timeType);
        replacement.setOverlapGroup(line.overlapGroup());
        replacement.setIncludedOpCodes(line.includedOperations().isEmpty() ? null : line.includedOperations());
        replacement.setSourceCode(sourceCode);
        replacement.setSourceRevision(importRow.getSourceRevision());
        replacement.setPublishedAt(line.publishedAt());
        replacement.setImportManifestId(importRow.getImportManifestId());
        standardRepository.save(replacement);
        return LineResult.WRITTEN;
    }

    private static boolean sameContent(ServiceLaborStandardEntity active, ProviderFeedLine line) {
        List<String> activeIncluded = active.getIncludedOpCodes() == null ? List.of() : active.getIncludedOpCodes();
        return active.getLaborHours().compareTo(line.hours()) == 0
                && Objects.equals(active.getOverlapGroup(), line.overlapGroup())
                && activeIncluded.equals(line.includedOperations())
                && Objects.equals(active.getPublishedAt(), line.publishedAt());
    }

    private void recordUnmapped(String sourceCode, LaborGuideImportEntity importRow, String providerOpCode) {
        Instant now = Instant.now(clock);
        LaborGuideUnmappedOperationEntity row = unmappedRepository
                .findBySourceCodeAndProviderOpCode(sourceCode, providerOpCode)
                .orElseGet(() -> {
                    LaborGuideUnmappedOperationEntity created = new LaborGuideUnmappedOperationEntity();
                    created.setSourceCode(sourceCode);
                    created.setProviderOpCode(providerOpCode);
                    created.setOccurrenceCount(0);
                    created.setFirstSeenAt(now);
                    return created;
                });
        row.setOccurrenceCount(row.getOccurrenceCount() + 1);
        row.setLastSeenAt(now);
        row.setLastManifestId(importRow.getImportManifestId());
        unmappedRepository.save(row);
    }

    private LaborGuideImportEntity persistProgress(LaborGuideImportEntity importRow, boolean terminal) {
        if (terminal) {
            boolean complete = importRow.getChunksApplied() == importRow.getExpectedChunkCount()
                    && importRow.getLinesApplied() == importRow.getExpectedLineCount();
            importRow.setStatus(
                    complete ? LaborGuideImportEntity.Status.COMPLETE : LaborGuideImportEntity.Status.INCOMPLETE);
            importRow.setCompletedAt(Instant.now(clock));
        }
        return importRepository.save(importRow);
    }

    @Override
    @NonNull
    public List<LaborGuideImportSummaryDto> listIncompleteImports() {
        return importRepository.findByStatusNotOrderByCreatedAtDesc(LaborGuideImportEntity.Status.COMPLETE).stream()
                .map(row -> toSummary(row, 0, 0))
                .toList();
    }

    @Override
    @NonNull
    public List<com.positivity.catalog.internal.dto.LaborGuideUnmappedOperationDto> listUnmappedOperations() {
        return unmappedRepository.findAllByOrderByLastSeenAtDesc().stream()
                .map(row -> {
                    var dto = new com.positivity.catalog.internal.dto.LaborGuideUnmappedOperationDto();
                    dto.setSourceCode(row.getSourceCode());
                    dto.setProviderOpCode(row.getProviderOpCode());
                    dto.setOccurrenceCount(row.getOccurrenceCount());
                    dto.setLastManifestId(row.getLastManifestId());
                    dto.setFirstSeenAt(row.getFirstSeenAt());
                    dto.setLastSeenAt(row.getLastSeenAt());
                    return dto;
                })
                .toList();
    }

    private LaborGuideImportSummaryDto toSummary(
            LaborGuideImportEntity row, long standardsWritten, long linesUnchanged) {
        LaborGuideImportSummaryDto dto = new LaborGuideImportSummaryDto();
        dto.setImportManifestId(row.getImportManifestId());
        dto.setSourceCode(row.getSourceCode());
        dto.setSourceRevision(row.getSourceRevision());
        dto.setStatus(row.getStatus().name());
        dto.setChunksApplied(row.getChunksApplied());
        dto.setExpectedChunkCount(row.getExpectedChunkCount());
        dto.setLinesApplied(row.getLinesApplied());
        dto.setExpectedLineCount(row.getExpectedLineCount());
        dto.setLinesUnmapped(row.getLinesUnmapped());
        dto.setStandardsWritten(standardsWritten);
        dto.setLinesUnchanged(linesUnchanged);
        dto.setCompletedAt(row.getCompletedAt());
        dto.setAlreadyImported(false);
        return dto;
    }
}
