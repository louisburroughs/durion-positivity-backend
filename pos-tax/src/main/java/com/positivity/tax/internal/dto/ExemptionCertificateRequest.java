package com.positivity.tax.internal.dto;

import com.positivity.tax.common.enums.ExemptionReasonCode;
import com.positivity.tax.internal.enums.ExemptionCertificateStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Create/update payload for a tax exemption certificate (story T3).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "ExemptionCertificateRequest", description = "Create or update a tax exemption certificate")
public class ExemptionCertificateRequest {

    @NotBlank(message = "customerId is required")
    @Schema(
            description = "Customer this certificate belongs to",
            example = "CUST-100234",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String customerId;

    @Schema(
            description = "State/region scope (subdivision code); null applies to all states",
            example = "CA",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String stateScope;

    @NotNull(message = "reasonCode is required")
    @Schema(
            description = "Exemption reason (RESALE, GOVERNMENT, NONPROFIT, AGRICULTURAL, OTHER)",
            example = "RESALE",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private ExemptionReasonCode reasonCode;

    @Schema(
            description = "Optional external/paper certificate number",
            example = "RESALE-2026-0001",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String certificateNumber;

    @NotNull(message = "effectiveFrom is required")
    @Schema(
            description = "Inclusive date the certificate becomes effective",
            example = "2026-01-01",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate effectiveFrom;

    @Schema(
            description = "Inclusive expiry date; null means no expiry",
            example = "2027-12-31",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private LocalDate expiresAt;

    @Schema(
            description = "Lifecycle status; defaults to ACTIVE when omitted on create",
            example = "ACTIVE",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private ExemptionCertificateStatus status;
}
