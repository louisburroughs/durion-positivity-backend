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
 * Request DTO for receiving a dispatched transfer order (odoo-parity C2, issue #1036).
 *
 * <p>{@code lines} pins per-line received quantities (each ≤ dispatched − already received);
 * lines omitted — or the whole field — default to their full un-received remainder.
 */
@Schema(description = "Request to receive a transfer order; omitted lines receive their full remainder")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceiveTransferOrderRequest {

    @Schema(
            description = "Explicit per-line received quantities; omitted lines receive their full remainder",
            requiredMode = NOT_REQUIRED)
    @Valid
    private List<TransferQuantityLineRequest> lines;
}
