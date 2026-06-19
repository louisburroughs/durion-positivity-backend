package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for approval configuration responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response DTO for approval configurations")
public class ApprovalConfigurationResponse {

    @NotNull
    @Schema(
            description = "Unique identifier for the configuration",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = REQUIRED)
    private UUID id;

    @Schema(
            description = "Location ID for this configuration (null = applies to all locations)",
            example = "00000000-0000-0000-0000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID locationId;

    @Schema(
            description = "Customer ID for this configuration (null = applies to all customers)",
            example = "00000000-0000-0000-0000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID customerId;

    @Schema(description = "Approval method", example = "CLICK_CONFIRM", requiredMode = NOT_REQUIRED)
    private String approvalMethod;

    @Schema(
            description = "Number of days a declined estimate can be reopened",
            example = "30",
            requiredMode = NOT_REQUIRED)
    private Integer declineExpiryDays;

    @Schema(description = "Whether signature is required", example = "false", requiredMode = NOT_REQUIRED)
    private Boolean requireSignature;

    @Schema(
            description = "Priority for configuration matching (0=default, 1=location-specific, 2=customer-specific)",
            example = "0",
            requiredMode = NOT_REQUIRED)
    private Integer priority;
}
