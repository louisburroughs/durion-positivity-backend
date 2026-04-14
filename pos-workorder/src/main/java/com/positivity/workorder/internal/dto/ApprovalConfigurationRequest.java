package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for creating or updating approval configurations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request DTO for approval configuration creation and updates")
public class ApprovalConfigurationRequest {

    @Schema(
            description = "Location ID for this configuration (null = applies to all locations)",
            example = "00000000-0000-0000-0000-000000000001")
    private UUID locationId;

    @Schema(
            description = "Customer ID for this configuration (null = applies to all customers)",
            example = "00000000-0000-0000-0000-000000000002")
    private UUID customerId;

    @NotNull(message = "approvalMethod is required")
    @Schema(description = "Approval method", example = "CLICK_CONFIRM")
    private String approvalMethod;

    @Min(value = 0, message = "declineExpiryDays must be 0 or greater")
    @Schema(description = "Number of days a declined estimate can be reopened", example = "30")
    private Integer declineExpiryDays;

    @Schema(description = "Whether signature is required", example = "false", defaultValue = "false", type = "boolean")
    private Boolean requireSignature;

    @Min(value = 0, message = "priority must be 0 or greater")
    @Schema(
            description = "Priority for configuration matching (0=default, 1=location-specific, 2=customer-specific)",
            example = "0")
    private Integer priority;
}
