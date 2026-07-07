package com.positivity.bulkloader.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Result of uploading a source file for a bulk load job")
public class FileUploadResponse {

    @Schema(
            description = "Identifier of the bulk load job the file was uploaded for",
            example = "00000000-0000-0000-0000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID jobId;

    @Schema(
            description = "Storage path where the uploaded file is persisted",
            example = "bulk-loads/00000000-0000-0000-0000-000000000001/products.csv",
            requiredMode = REQUIRED)
    @NotNull
    private String storagePath;

    @Schema(
            description = "Original name of the uploaded file",
            example = "products-2026-01.csv",
            requiredMode = REQUIRED)
    @NotNull
    private String fileName;

    @Schema(description = "Size of the uploaded file in bytes", example = "204800", requiredMode = REQUIRED)
    private long sizeBytes;

    @Schema(
            description = "Content detection result for the uploaded file (detected domain, confidence, and"
                    + " suggested column mappings). Absent when the file structure could not be parsed.",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private ContentDetectionResult detection;
}
