package com.positivity.inventory.internal.dto.purchaseorder;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RevisePurchaseOrderRequest {

    @NotNull
    private LocalDate poDate;

    private String paymentTermsId;

    private LocalDate expectedDeliveryDate;

    private UUID shipToLocationId;

    private String requestedBy;

    private String comment;

    @NotNull
    @NotEmpty
    @Valid
    private List<PurchaseOrderLineRequest> lines;

    @NotNull
    private String revisionReason;
}
