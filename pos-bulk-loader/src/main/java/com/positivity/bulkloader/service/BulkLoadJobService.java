package com.positivity.bulkloader.service;

import com.positivity.bulkloader.internal.dto.BulkLoadJobCreateRequest;
import com.positivity.bulkloader.internal.dto.BulkLoadJobResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface BulkLoadJobService {

    BulkLoadJobResponse createJob(@NonNull BulkLoadJobCreateRequest request, @NonNull String operatorId);

    BulkLoadJobResponse getJob(@NonNull UUID jobId);

    Page<BulkLoadJobResponse> listJobsForOperator(@NonNull String operatorId, @NonNull Pageable pageable);

    BulkLoadJobResponse cancelJob(@NonNull UUID jobId, @NonNull String operatorId);

    void startProcessing(@NonNull UUID jobId, @NonNull String operatorId);
}
