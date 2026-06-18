package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request payload for creating an estimate")
public class CreateEstimateRequest {
    @NotNull(message = "customerId is required")
    @Schema(
            description = "Customer identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID customerId;

    @NotNull(message = "vehicleId is required")
    @Schema(
            description = "Vehicle identifier",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    private UUID vehicleId;

    @Schema(
            description = "Optional location identifier; defaults from session when omitted",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private UUID locationId; // Optional - will use default from session if not provided

    @Schema(description = "Optional currency code; defaults when omitted", example = "USD", requiredMode = NOT_REQUIRED)
    private String currencyUomId; // Optional - will use default if not provided

    @Schema(
            description = "Optional tax region identifier; defaults when omitted",
            example = "01960003-0000-7000-8000-000000000004",
            requiredMode = NOT_REQUIRED)
    private UUID taxRegionId; // Optional - will use default if not provided

    @Schema(description = "Optional subtotal amount before tax", example = "500.00", requiredMode = NOT_REQUIRED)
    private BigDecimal subtotal;

    @Schema(description = "Optional tax amount", example = "50.00", requiredMode = NOT_REQUIRED)
    private BigDecimal taxAmount;

    @Schema(description = "Optional total amount including tax", example = "550.00", requiredMode = NOT_REQUIRED)
    private BigDecimal total;

    @NotBlank(message = "crmPartyId is required")
    @Size(max = 36, message = "crmPartyId must be at most 36 characters")
    @Schema(
            description = "CRM party identifier (UUIDv7 string)",
            example = "01952f4e-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private String crmPartyId;

    @NotBlank(message = "crmVehicleId is required")
    @Size(max = 36, message = "crmVehicleId must be at most 36 characters")
    @Schema(
            description = "CRM vehicle identifier (UUIDv7 string)",
            example = "01952f4e-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    private String crmVehicleId;

    @NotNull(message = "crmContactIds is required")
    @Builder.Default
    @Schema(
            description = "List of CRM contact identifiers (may be empty)",
            example = "[\"01952f4e-0000-7000-8000-000000000003\"]",
            requiredMode = REQUIRED)
    private List<String> crmContactIds = new ArrayList<>();
}
