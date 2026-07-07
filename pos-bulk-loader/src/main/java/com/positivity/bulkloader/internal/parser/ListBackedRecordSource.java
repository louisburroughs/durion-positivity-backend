package com.positivity.bulkloader.internal.parser;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Record source over records that were fully materialized during parsing. */
final class ListBackedRecordSource implements RecordSource {

    private final List<String> headers;
    private final Iterator<Map<String, String>> records;

    ListBackedRecordSource(@NonNull List<String> headers, @NonNull List<Map<String, String>> records) {
        this.headers = List.copyOf(headers);
        this.records = records.iterator();
    }

    @Override
    @NonNull
    public List<String> headers() {
        return headers;
    }

    @Override
    @Nullable
    public Map<String, String> nextRecord() {
        return records.hasNext() ? records.next() : null;
    }

    @Override
    public void close() {
        // Nothing to release; content is already materialized.
    }
}
