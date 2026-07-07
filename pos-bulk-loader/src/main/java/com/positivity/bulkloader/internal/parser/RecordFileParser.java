package com.positivity.bulkloader.internal.parser;

import java.io.InputStream;
import org.jspecify.annotations.NonNull;

/** Parses one physical file format (by extension) into a {@link RecordSource}. */
public interface RecordFileParser {

    boolean supports(@NonNull String fileName);

    /**
     * Opens the given content as a record source. Ownership of the stream passes to the returned
     * source; closing the source closes the stream.
     */
    @NonNull
    RecordSource open(@NonNull InputStream content, @NonNull String fileName);
}
