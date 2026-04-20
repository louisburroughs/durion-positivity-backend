package com.positivity.bulkloader.service;

import com.positivity.bulkloader.internal.dto.AuditRecordResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.core.io.Resource;

public interface ReviewQueueService {

    List<AuditRecordResponse> getAuditRecords(@NonNull UUID jobId);

    Resource generateErrorReport(@NonNull UUID jobId);
}
