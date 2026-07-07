package com.positivity.bulkloader.service;

import com.positivity.bulkloader.internal.dto.ColumnMappingApproveRequest;
import com.positivity.bulkloader.internal.dto.ColumnMappingResponse;
import com.positivity.bulkloader.internal.dto.ContentDetectionResult;
import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface ColumnMappingService {

    @NonNull
    List<ColumnMappingResponse> getMappingsForJob(@NonNull UUID jobId, @NonNull String operatorId);

    @NonNull
    List<ColumnMappingResponse> approveMappings(
            @NonNull UUID jobId, @NonNull String operatorId, @NonNull ColumnMappingApproveRequest request);

    /**
     * Resolves the source-column-to-target-field mappings to use when processing a file with the
     * given headers: mappings persisted for the job (user-approved or previously suggested) take
     * priority; headers not covered fall back to rule-based inference for the domain. Each target
     * field is claimed by at most one source column.
     */
    @NonNull
    Map<String, String> resolveEffectiveMappings(
            @Nullable UUID jobId, @NonNull List<String> columnHeaders, @NonNull DomainType domainType);

    /**
     * Parses the uploaded file's structure (headers or document shape), runs content detection,
     * and persists suggested column mappings for the job unless the operator has already approved
     * mappings. Returns {@code null} when the file cannot be parsed — detection is best-effort and
     * must never fail the upload.
     */
    @Nullable
    ContentDetectionResult detectUploadedFile(
            @NonNull UUID jobId, @NonNull String operatorId, @NonNull String storagePath, @NonNull String fileName);
}
