package com.positivity.bulkloader.internal.config;

import com.positivity.bulkloader.internal.domain.DomainLoaderStrategy;
import com.positivity.bulkloader.internal.domain.NumberedRecord;
import com.positivity.bulkloader.internal.domain.ResolutionContext;
import com.positivity.bulkloader.internal.parser.FlexibleRecordItemReader;
import com.positivity.bulkloader.internal.parser.RecordFileParserRegistry;
import com.positivity.bulkloader.internal.service.BulkIngestResultRecorder;
import com.positivity.bulkloader.internal.service.BulkLoadJobExecutionListener;
import com.positivity.bulkloader.internal.service.ColumnMappingService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

/**
 * Builds the job, step, reader and processor every loader domain needs.
 *
 * <p>The four are identical across domains bar their types and their strategy, so holding them here
 * keeps a new domain down to the beans Spring genuinely has to see by name, instead of a fifth copy
 * of the same builder chain. It also means every domain now reads its file through
 * {@link FlexibleRecordItemReader}: columns are discovered from the file's own headers and mapped
 * through the job's approved mappings, rather than being taken positionally. Positional reading was
 * the reason a fixture could not reorder or omit a column, and why only CSV was ever accepted.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BulkLoadJobFactory {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final BulkLoadJobExecutionListener bulkLoadJobExecutionListener;
    private final RecordFileParserRegistry recordFileParserRegistry;
    private final ColumnMappingService columnMappingService;
    private final BulkIngestResultRecorder resultRecorder;
    private final AuthorizationHeaderRelay headerRelay;

    @Value("${bulk-loader.storage.local-root:/tmp/bulk-loader}")
    private String storageRoot;

    /** Chunk size shared by every domain; the ingest endpoints are batch-shaped, not row-shaped. */
    private static final int CHUNK_SIZE = 500;

    @NonNull
    public Job job(@NonNull String name, @NonNull Step step) {
        return new JobBuilder(name, jobRepository)
                .listener(bulkLoadJobExecutionListener)
                .start(step)
                .build();
    }

    /**
     * A fault-tolerant step that skips bad rows rather than abandoning the file.
     *
     * <p>The skip limit is deliberately unbounded: a bulk import is expected to contain rows the
     * owning service will refuse, and the operator reviews them afterwards from the audit trail.
     */
    @NonNull
    public <T> Step step(
            @NonNull String name,
            @NonNull ItemStreamReader<T> reader,
            @NonNull ItemProcessor<T, NumberedRecord<T>> processor,
            @NonNull ItemWriter<NumberedRecord<T>> writer) {
        return new StepBuilder(name, jobRepository)
                .<T, NumberedRecord<T>>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .faultTolerant()
                .skipLimit(Integer.MAX_VALUE)
                .skip(Exception.class)
                .build();
    }

    /**
     * A header-driven reader for one domain: delimited text (CSV/TSV/pipe), spreadsheets, JSON,
     * YAML or XML, with columns mapped through the job's approved mappings or rule-based inference.
     */
    @NonNull
    public <T> ItemStreamReader<T> reader(
            @NonNull DomainLoaderStrategy<T> strategy, @NonNull String storagePath, @Nullable String jobIdParam) {

        Path resolved = resolveStoragePath(storagePath);
        String fileName = resolved.getFileName().toString();
        UUID jobId = parseUuidOrNull(jobIdParam);

        return new FlexibleRecordItemReader<>(
                () -> {
                    try {
                        return recordFileParserRegistry.open(Files.newInputStream(resolved), fileName);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to open uploaded file: " + fileName, e);
                    }
                },
                headers -> columnMappingService.resolveEffectiveMappings(jobId, headers, strategy.getDomainType()),
                strategy::mapRow);
    }

    /**
     * Validates each row, resolves its business keys, validates again, and stamps it with the line
     * it came from.
     *
     * <p>Resolution runs before validation, not after. A file keyed by business names has no id to
     * validate until those names are looked up, so validating first would reject every row of it.
     * Running the other way round also means a strategy reports an unresolvable key by simply
     * leaving the id unset, and the ordinary "id is required" rule catches it — there is no second
     * vocabulary for "resolved to nothing", and no way for a row to be posted with the field
     * silently empty.
     *
     * <p>Returning null is how Spring Batch counts a row as skipped, keeping a bad row out of the
     * chunk without failing the file. Each skip also writes an audit row, so a rejected row is
     * something an operator can see and correct rather than a line in a log.
     *
     * @param jobId the bulk-load job, or null when it could not be parsed — rejections are then
     *     logged only, since an audit row has nowhere to belong
     */
    @NonNull
    public <T> ItemProcessor<T, NumberedRecord<T>> processor(
            @NonNull DomainLoaderStrategy<T> strategy,
            @Nullable UUID jobId,
            @Nullable ResolutionContext resolutionContext) {

        AtomicLong rowCursor = new AtomicLong();
        return item -> {
            long rowNumber = rowCursor.getAndIncrement();

            T resolved = resolutionContext == null ? item : strategy.resolve(item, resolutionContext);

            List<String> errors = strategy.validate(resolved);
            if (!errors.isEmpty()) {
                return reject(strategy, jobId, rowNumber, resolved, errors);
            }
            return new NumberedRecord<>(rowNumber, resolved);
        };
    }

    @Nullable
    private <T> NumberedRecord<T> reject(
            DomainLoaderStrategy<T> strategy, @Nullable UUID jobId, long rowNumber, T item, List<String> errors) {
        String message = String.join("; ", errors);
        log.warn("{} row {} rejected: {}", strategy.getDomainType(), rowNumber, message);
        if (jobId != null) {
            resultRecorder.recordRejected(
                    jobId, strategy.getDomainType(), rowNumber, item, "BULK_LOAD_VALIDATION_FAILED", message);
        }
        return null;
    }

    /**
     * The lookup context for one step, or null when the job has no location — resolution is scoped
     * to the job's location, and a strategy that needs one cannot work without it.
     */
    @Nullable
    public ResolutionContext resolutionContext(RestClient.Builder restClientBuilder, @Nullable String locationIdParam) {
        UUID locationId = parseUuidOrNull(locationIdParam);
        return locationId == null ? null : new RestResolutionContext(restClientBuilder, headerRelay, locationId);
    }

    /** Resolves an uploaded file inside the storage root, refusing anything that escapes it. */
    private Path resolveStoragePath(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            throw new IllegalArgumentException("storagePath job parameter must not be null or blank");
        }
        Path base = Path.of(storageRoot).normalize().toAbsolutePath();
        Path resolved = base.resolve(storagePath).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Invalid storage path: attempted path traversal");
        }
        return resolved;
    }

    /** The job's id from its Spring Batch parameter, or null when absent or malformed. */
    @Nullable
    public UUID parseJobId(@Nullable String jobIdParam) {
        return parseUuidOrNull(jobIdParam);
    }

    @Nullable
    private UUID parseUuidOrNull(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}
