package com.positivity.bulkloader.internal.dto;

import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FileUploadResponse {

    private UUID jobId;
    private String storagePath;
    private String fileName;
    private long sizeBytes;
}
