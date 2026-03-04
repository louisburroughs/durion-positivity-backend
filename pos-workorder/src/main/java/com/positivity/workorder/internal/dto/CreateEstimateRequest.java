package com.positivity.workorder.internal.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
    @Schema(description = "Customer identifier", example = "550e8400-e29b-41d4-a716-446655440001")
    private UUID customerId;

    @NotNull(message = "vehicleId is required")
    @Schema(description = "Vehicle identifier", example = "550e8400-e29b-41d4-a716-446655440002")
    private UUID vehicleId;

    @Schema(description = "Optional location identifier; defaults from session when omitted", example = "550e8400-e29b-41d4-a716-446655440003")
    private UUID locationId; // Optional - will use default from session if not provided

    @Schema(description = "Optional currency code; defaults when omitted", example = "USD")
    private String currencyUomId; // Optional - will use default if not provided

    @Schema(description = "Optional tax region identifier; defaults when omitted", example = "550e8400-e29b-41d4-a716-446655440004")
    private UUID taxRegionId; // Optional - will use default if not provided

    @Schema(description = "Optional subtotal amount before tax", example = "500.00")
    private BigDecimal subtotal;

    @Schema(description = "Optional tax amount", example = "50.00")
    private BigDecimal taxAmount;

    @Schema(description = "Optional total amount including tax", example = "550.00")
    private BigDecimal total;

    @NotBlank(message = "crmPartyId is required")
    @Schema(description = "CRM party identifier (UUIDv7 string)", example = "01952f4e-0000-7000-8000-000000000001")
    private String crmPartyId;

    @NotBlank(message = "crmVehicleId is required")
    @Schema(description = "CRM vehicle identifier (UUIDv7 string)", example = "01952f4e-0000-7000-8000-000000000002")
    private String crmVehicleId;

    @NotNull(message = "crmContactIds is required")
    @Builder.Default
    @Schema(description = "List of CRM contact identifiers (may be empty)", example = "[\"01952f4e-0000-7000-8000-000000000003\"]")
    private List<String> crmContactIds = new ArrayList<>();
}
