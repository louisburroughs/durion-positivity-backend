package com.positivity.bulkloader.service;

import com.positivity.bulkloader.internal.dto.AuditRecordResponse;
import com.positivity.bulkloader.internal.dto.BulkCorrectionRequest;
import com.positivity.bulkloader.internal.dto.BulkCorrectionResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.core.io.Resource;

public interface ReviewQueueService {

    List<AuditRecordResponse> getAuditRecords(@NonNull UUID jobId);

    Resource generateErrorReport(@NonNull UUID jobId);

    BulkCorrectionResponse submitCorrections(
            @NonNull UUID jobId, @NonNull BulkCorrectionRequest request, @NonNull String operatorId);
}
