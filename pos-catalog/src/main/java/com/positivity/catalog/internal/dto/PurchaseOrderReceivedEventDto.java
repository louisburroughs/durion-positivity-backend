package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Purchase order received event data")
public class PurchaseOrderReceivedEventDto {

    @NotNull
    private UUID itemId;

    @NotNull
    private UUID supplierId;

    @NotNull
    @Positive
    private BigDecimal receivedQty;

    @NotNull
    @Positive
    private BigDecimal receivedUnitCost;

    @NotNull
    private String purchaseOrderId;
}
