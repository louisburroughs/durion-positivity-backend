package com.positivity.bulkloader.internal.enums;

public enum JobStatus {
    CREATED,
    UPLOADING,
    DETECTING,
    MAPPING_REVIEW,
    DEDUP,
    PROCESSING,
    COMPLETED,
    /**
     * The batch ran to completion but at least one row was rejected — by validation before the
     * call, or by the owning service once the chunk was posted. Distinct from {@link #FAILED},
     * which means the batch itself did not finish. Both are terminal, and both accept corrections
     * and a retry.
     */
    PARTIAL,
    FAILED,
    CANCELLED
}
