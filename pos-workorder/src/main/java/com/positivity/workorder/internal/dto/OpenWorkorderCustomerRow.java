package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** One customer's open work-order count (#1855). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "One customer and how many open work orders they currently hold")
public class OpenWorkorderCustomerRow {

    @Schema(description = "Customer (party) id")
    private UUID customerId;

    @Nullable
    @Schema(description = "Customer display name, resolved server-side; null when the party replica has no record")
    private String customerName;

    @Schema(description = "Open work orders this customer currently holds")
    private long openWorkorders;
}
