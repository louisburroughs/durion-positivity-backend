package com.positivity.inventory.internal.dto.transfer;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for dispatching a transfer order (odoo-parity C2, issue #1036).
 *
 * <p>{@code lines} pins per-line dispatch quantities (each ≤ its requested quantity); lines
 * omitted — or the whole field — default to their full requested quantity.
 */
@Schema(description = "Request to dispatch a transfer order; omitted lines dispatch their full requested quantity")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DispatchTransferOrderRequest {

    @Schema(
            description = "Explicit per-line dispatch quantities; omitted lines dispatch full requested",
            requiredMode = NOT_REQUIRED)
    @Valid
    private List<TransferQuantityLineRequest> lines;
}
