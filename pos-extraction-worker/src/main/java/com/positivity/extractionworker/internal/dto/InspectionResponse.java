package com.positivity.extractionworker.internal.dto;

import com.positivity.extractionworker.internal.enums.ReasonCode;
import com.positivity.extractionworker.internal.enums.Verdict;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * The verdict on one uploaded file. No pages and no copy of the file are returned (S25 ruling 1).
 *
 * @param verdict whether the file may be read further
 * @param reasonCode why it was rejected; absent when accepted
 * @param detectedType the type the bytes show, or UNKNOWN
 * @param pageCount PDF pages, TIFF frames, 1 for a single image, data rows for CSV and XLSX; 0 when rejected
 * @param sha256 lowercase hex SHA-256 of the uploaded bytes
 */
public record InspectionResponse(
        @Schema(description = "Whether the file may be read further.", example = "ACCEPTED") Verdict verdict,
        @Schema(description = "Why the file was rejected. Absent when it was accepted.", example = "PAGE_LIMIT_EXCEEDED")
                @Nullable ReasonCode reasonCode,
        @Schema(
                        description = "The type the bytes show: PDF, JPEG, PNG, HEIC, TIFF, CSV, XLSX or UNKNOWN.",
                        example = "PDF")
                String detectedType,
        @Schema(
                        description =
                                "PDF pages, TIFF frames, 1 for a single image, or the count of data rows (all rows"
                                        + " after the first) for CSV and XLSX. 0 when the file is rejected.",
                        example = "3")
                int pageCount,
        @Schema(
                        description = "Lowercase hex SHA-256 of the uploaded bytes.",
                        example = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")
                String sha256) {}
