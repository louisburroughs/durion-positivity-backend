package com.positivity.bulkloader.internal.parser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamReader;

/**
 * Spring Batch reader over a {@link RecordSource}: the file structure is discovered from headers
 * (or document shape), source columns are renamed to canonical target fields via the resolved
 * mapping, and each canonical row is converted to a domain record.
 *
 * @param <T> domain record type produced per row
 */
@Slf4j
public class FlexibleRecordItemReader<T> implements ItemStreamReader<T> {

    private final Supplier<RecordSource> sourceSupplier;
    private final Function<List<String>, Map<String, String>> mappingResolver;
    private final Function<Map<String, String>, T> rowMapper;

    private RecordSource source;
    private Map<String, String> columnMappings;

    public FlexibleRecordItemReader(
            @NonNull Supplier<RecordSource> sourceSupplier,
            @NonNull Function<List<String>, Map<String, String>> mappingResolver,
            @NonNull Function<Map<String, String>, T> rowMapper) {
        this.sourceSupplier = sourceSupplier;
        this.mappingResolver = mappingResolver;
        this.rowMapper = rowMapper;
    }

    @Override
    public void open(@NonNull ExecutionContext executionContext) {
        try {
            source = sourceSupplier.get();
            columnMappings = mappingResolver.apply(source.headers());
        } catch (RuntimeException e) {
            closeQuietly();
            throw new ItemStreamException("Failed to open uploaded file for reading", e);
        }
        if (columnMappings.isEmpty()) {
            List<String> headers = source.headers();
            closeQuietly();
            throw new ItemStreamException("No source columns could be mapped to target fields; headers=" + headers);
        }
        log.info("Opened flexible record reader with column mappings {}", columnMappings);
    }

    @Override
    @Nullable
    public T read() {
        Map<String, String> row = source.nextRecord();
        if (row == null) {
            return null;
        }
        Map<String, String> canonical = LinkedHashMap.newLinkedHashMap(columnMappings.size());
        for (Map.Entry<String, String> mapping : columnMappings.entrySet()) {
            String value = row.get(mapping.getKey());
            if (value != null) {
                canonical.put(mapping.getValue(), value);
            }
        }
        return rowMapper.apply(canonical);
    }

    @Override
    public void update(@NonNull ExecutionContext executionContext) {
        // No restart state: the reader always re-reads from the start of the file.
    }

    @Override
    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        if (source != null) {
            try {
                source.close();
            } catch (RuntimeException e) {
                log.warn("Failed to close record source: {}", e.getMessage());
            } finally {
                source = null;
            }
        }
    }
}
