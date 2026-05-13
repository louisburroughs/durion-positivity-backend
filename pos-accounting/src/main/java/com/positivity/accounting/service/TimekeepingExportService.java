package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.ExportJobRequest;
import com.positivity.accounting.internal.dto.ExportJobResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface TimekeepingExportService {
    @NonNull
    ExportJobResponse requestExport(@NonNull ExportJobRequest request);

    @NonNull
    ExportJobResponse getExportStatus(@NonNull UUID jobId);

    @NonNull
    Page<ExportJobResponse> listExportHistory(@NonNull Pageable pageable);
}
