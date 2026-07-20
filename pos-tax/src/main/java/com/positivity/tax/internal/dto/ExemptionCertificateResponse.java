package com.positivity.tax.internal.dto;

import com.positivity.tax.common.enums.ExemptionReasonCode;
import com.positivity.tax.internal.entity.ExemptionCertificate;
import com.positivity.tax.internal.enums.ExemptionCertificateStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Read model for a tax exemption certificate (story T3).
 *
 * @param id                 certificate identifier (UUID v7)
 * @param customerId         owning customer
 * @param stateScope         state/region scope; {@code null} applies to all states
 * @param reasonCode         exemption reason
 * @param certificateNumber  optional external/paper certificate number
 * @param effectiveFrom      inclusive effective date
 * @param expiresAt          inclusive expiry date; {@code null} means no expiry
 * @param status             lifecycle status
 * @param createdAt          creation timestamp
 * @param updatedAt          last-modification timestamp
 */
@Schema(name = "ExemptionCertificateResponse", description = "A tax exemption certificate")
public record ExemptionCertificateResponse(
        @Schema(description = "Certificate identifier", example = "018f0000-0000-7000-8000-0000000000aa")
        UUID id,

        @Schema(description = "Owning customer", example = "CUST-100234")
        String customerId,

        @Schema(description = "State/region scope; null = all states", example = "CA")
        String stateScope,

        @Schema(description = "Exemption reason", example = "RESALE")
        ExemptionReasonCode reasonCode,

        @Schema(description = "External/paper certificate number", example = "RESALE-2026-0001")
        String certificateNumber,

        @Schema(description = "Inclusive effective date", example = "2026-01-01")
        LocalDate effectiveFrom,

        @Schema(description = "Inclusive expiry date; null = no expiry", example = "2027-12-31")
        LocalDate expiresAt,

        @Schema(description = "Lifecycle status", example = "ACTIVE")
        ExemptionCertificateStatus status,

        @Schema(description = "Creation timestamp") Instant createdAt,
        @Schema(description = "Last-modification timestamp") Instant updatedAt) {

    /**
     * Map a persisted entity to its read model.
     *
     * @param entity the certificate entity
     * @return the response view
     */
    @NonNull
    public static ExemptionCertificateResponse from(@NonNull ExemptionCertificate entity) {
        return new ExemptionCertificateResponse(
                entity.getId(),
                entity.getCustomerId(),
                entity.getStateScope(),
                entity.getReasonCode(),
                entity.getCertificateNumber(),
                entity.getEffectiveFrom(),
                entity.getExpiresAt(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
