package com.positivity.bulkloader.service;

import com.positivity.bulkloader.internal.dto.ColumnMappingApproveRequest;
import com.positivity.bulkloader.internal.dto.ColumnMappingResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface ColumnMappingService {

    @NonNull
    List<ColumnMappingResponse> getMappingsForJob(@NonNull UUID jobId, @NonNull String operatorId);

    @NonNull
    List<ColumnMappingResponse> approveMappings(@NonNull UUID jobId, @NonNull String operatorId, @NonNull ColumnMappingApproveRequest request);
}
