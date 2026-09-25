package com.positivity.bulkloader.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.bulkloader.internal.enums.CorrectionStatus;
import com.positivity.bulkloader.internal.enums.ReviewStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// Fields below mirror AuditRecordResponse (plus correctedValues, which that DTO omits) so the
// frontend can splice the corrected record back into its review-queue list without a follow-up
// fetch. Flat fields were chosen over nesting an AuditRecordResponse because correctedValues has
// no home on that DTO and duplicating auditRecordId/id would be redundant.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Result of a single correction record submission")
public class CorrectionResultDto {

    @Schema(
            description = "ID of the audit record that was corrected",
            example = "00000000-0000-0000-0000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID auditRecordId;

    @Schema(
            description = "Whether the correction was accepted or rejected",
            example = "ACCEPTED",
            requiredMode = REQUIRED)
    @NotNull
    private CorrectionStatus status;

    @Schema(
            description = "Reason for rejection if status is REJECTED",
            example = "Audit record does not belong to this job",
            nullable = true,
            requiredMode = NOT_REQUIRED)
    private String rejectionReason;

    @Schema(
            description = "Type of the target entity for the processed row",
            example = "CATALOG_PRODUCT",
            nullable = true,
            requiredMode = NOT_REQUIRED)
    private String entityType;

    @Schema(
            description = "Identifier of the entity created or updated from the row, if any",
            example = "00000000-0000-0000-0000-000000000003",
            nullable = true,
            requiredMode = NOT_REQUIRED)
    private UUID entityId;

    @Schema(
            description = "One-based row number within the source file",
            example = "42",
            nullable = true,
            requiredMode = NOT_REQUIRED)
    private Long rowNumber;

    @Schema(
            description = "Review status of the audit record",
            example = "PENDING",
            nullable = true,
            requiredMode = NOT_REQUIRED)
    private ReviewStatus reviewStatus;

    @Schema(
            description = "Machine-readable reason codes describing why the row needs review",
            example = "MISSING_REQUIRED_FIELD,INVALID_PRICE",
            nullable = true,
            requiredMode = NOT_REQUIRED)
    private String reasonCodes;

    @Schema(
            description = "Serialized original values from the source row",
            example = "{\"sku\": \"PROD-001\", \"price\": \"\"}",
            nullable = true,
            requiredMode = NOT_REQUIRED)
    private String originalValues;

    @Schema(
            description = "Serialized corrected values submitted for the row, if any",
            example = "{\"sku\": \"PROD-001\", \"price\": \"19.99\"}",
            nullable = true,
            requiredMode = NOT_REQUIRED)
    private String correctedValues;

    @Schema(
            description = "Timestamp when the audit record was created (ISO 8601)",
            example = "2026-01-15T09:30:00Z",
            nullable = true,
            requiredMode = NOT_REQUIRED)
    private Instant createdAt;
}
