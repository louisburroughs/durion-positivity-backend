package com.positivity.bulkloader.service;

import com.positivity.bulkloader.internal.dto.BulkLoadJobCreateRequest;
import com.positivity.bulkloader.internal.dto.BulkLoadJobResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface BulkLoadJobService {

    BulkLoadJobResponse createJob(@NonNull BulkLoadJobCreateRequest request, @NonNull String operatorId);

    void markUploadStored(@NonNull UUID jobId, @NonNull String operatorId, @NonNull String storagePath);

    BulkLoadJobResponse getJob(@NonNull UUID jobId, @NonNull String operatorId);

    Page<BulkLoadJobResponse> listJobsForOperator(@NonNull String operatorId, @NonNull Pageable pageable);

    BulkLoadJobResponse cancelJob(@NonNull UUID jobId, @NonNull String operatorId);

    BulkLoadJobResponse retryJob(@NonNull UUID jobId, @NonNull String operatorId);

    void startProcessing(@NonNull UUID jobId, @NonNull String operatorId, @Nullable String authorizationHeader);
}
