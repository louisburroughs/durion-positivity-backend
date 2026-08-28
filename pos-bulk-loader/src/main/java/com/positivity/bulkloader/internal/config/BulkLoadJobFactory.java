package com.positivity.bulkloader.internal.config;

import com.positivity.bulkloader.internal.domain.DomainLoaderStrategy;
import com.positivity.bulkloader.internal.parser.FlexibleRecordItemReader;
import com.positivity.bulkloader.internal.parser.RecordFileParserRegistry;
import com.positivity.bulkloader.internal.service.BulkLoadJobExecutionListener;
import com.positivity.bulkloader.internal.service.ColumnMappingService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
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
            @NonNull ItemProcessor<T, T> processor,
            @NonNull ItemWriter<T> writer) {
        return new StepBuilder(name, jobRepository)
                .<T, T>chunk(CHUNK_SIZE)
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
     * Drops rows the strategy rejects. Returning null is how Spring Batch counts a row as skipped,
     * which is what keeps a bad row out of the chunk without failing the file.
     */
    @NonNull
    public <T> ItemProcessor<T, T> processor(@NonNull DomainLoaderStrategy<T> strategy) {
        return item -> {
            List<String> errors = strategy.validate(item);
            if (!errors.isEmpty()) {
                log.warn("{} record validation failed: {}", strategy.getDomainType(), errors);
                return null;
            }
            return item;
        };
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
