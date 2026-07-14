package com.positivity.bulkloader.internal.parser;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Forward-only cursor over the records discovered in an uploaded file, independent of the physical
 * file format. Keys of each record are the source column names (CSV/spreadsheet headers) or the
 * flattened field paths (JSON/YAML/XML).
 */
public interface RecordSource extends AutoCloseable {

    @NonNull
    List<String> headers();

    /** Returns the next record as source-column-to-raw-value pairs, or {@code null} when exhausted. */
    @Nullable
    Map<String, String> nextRecord();

    @Override
    void close();
}
