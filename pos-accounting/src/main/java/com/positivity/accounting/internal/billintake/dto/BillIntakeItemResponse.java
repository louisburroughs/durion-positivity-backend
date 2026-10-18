package com.positivity.accounting.internal.billintake.dto;

import com.positivity.accounting.internal.billintake.enums.BillIntakeChannel;
import com.positivity.accounting.internal.billintake.enums.BillIntakeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One bill draft as the upload answers it (CAP:550 S25, #2518). The reference is the business name of the draft (messages
 * and logs use it); the ids are for routing only (ADR-0064).
 */
@Schema(description = "A bill draft created by an upload. Use intakeReference to talk about it, intakeId to route.")
public record BillIntakeItemResponse(
        @Schema(description = "Routing id of the draft", requiredMode = Schema.RequiredMode.REQUIRED) UUID intakeId,
        @Schema(description = "Business reference, BI-YYYYMM-nnnnnnn", example = "BI-202610-0000012") String intakeReference,
        @Schema(description = "How the draft arrived") BillIntakeChannel channel,
        @Schema(description = "QUARANTINED while the file waits to be scanned and read; FILE_REJECTED when it was refused")
                BillIntakeStatus status,
        @Schema(description = "Why the file was refused, when status is FILE_REJECTED", nullable = true)
                @Nullable String rejectionReason,
        @Schema(description = "SHA-256 of the file, lower-case hex", nullable = true) @Nullable String sha256,
        @Schema(description = "When the file was received") Instant uploadedAt) {}
