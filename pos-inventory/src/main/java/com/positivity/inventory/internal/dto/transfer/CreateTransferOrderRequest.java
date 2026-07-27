package com.positivity.inventory.internal.dto.transfer;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating a cross-site transfer order (odoo-parity C1, issue #1035).
 */
@Schema(description = "Request to create a cross-site transfer order in DRAFT")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateTransferOrderRequest {

    @Schema(
            description = "Source site the transfer ships from",
            example = "01960004-0001-7000-8000-00000000000a",
            requiredMode = REQUIRED)
    @NotNull(message = "Source location ID is required")
    private UUID sourceLocationId;

    @Schema(
            description = "Optional bin-level source storage location under the source site",
            example = "01960004-0001-7000-8000-00000000000b",
            requiredMode = NOT_REQUIRED)
    private UUID sourceStorageLocationId;

    @Schema(
            description = "Destination site the transfer ships to",
            example = "01960004-0001-7000-8000-00000000000c",
            requiredMode = REQUIRED)
    @NotNull(message = "Destination location ID is required")
    private UUID destinationLocationId;

    @Schema(
            description = "Optional bin-level destination storage location under the destination site",
            example = "01960004-0001-7000-8000-00000000000d",
            requiredMode = NOT_REQUIRED)
    private UUID destinationStorageLocationId;

    @Schema(description = "Requested SKU lines; at least one", requiredMode = REQUIRED)
    @NotEmpty(message = "At least one line is required")
    @Valid
    private List<TransferOrderLineRequest> lines;

    @Schema(description = "Free-text notes", example = "Weekly resupply run", requiredMode = NOT_REQUIRED)
    @Size(max = 2000, message = "Notes must not exceed 2000 characters")
    private String notes;
}
