package com.positivity.bulkloader.internal.config;

import com.positivity.events.EventTypeRegistration;
import java.util.List;

public final class BulkLoaderEventTypes {

    private BulkLoaderEventTypes() {}

    public static List<EventTypeRegistration> all() {
        return List.of(
                EventTypeRegistration.write("BULK_LOADER_JOB_CREATE", "Create a new bulk load job")
                        .build(),
                EventTypeRegistration.write("BULK_LOADER_JOB_START", "Start processing a bulk load job")
                        .build(),
                EventTypeRegistration.approval("BULK_LOADER_JOB_COMPLETE", "Bulk load job completed successfully")
                        .build(),
                EventTypeRegistration.write("BULK_LOADER_JOB_CANCEL", "Cancel a bulk load job")
                        .build(),
                EventTypeRegistration.write("BULK_LOADER_FILE_UPLOAD", "File uploaded for bulk load job")
                        .build(),
                EventTypeRegistration.approval("BULK_LOADER_MAPPING_APPROVE", "Column mappings approved by operator")
                        .build());
    }
}
