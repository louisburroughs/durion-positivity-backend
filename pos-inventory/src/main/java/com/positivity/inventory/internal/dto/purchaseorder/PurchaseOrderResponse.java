package com.positivity.inventory.internal.dto.purchaseorder;

import com.positivity.inventory.internal.enums.PurchaseOrderStatus;
import java.time.Instant;
import java.time.LocalDate;
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
public class PurchaseOrderResponse {
    private UUID purchaseOrderId;
    private UUID vendorId;
    private String poNumber;
    private PurchaseOrderStatus status;
    private Integer versionNumber;
    private String currency;
    private Long subtotalMinor;
    private Long taxMinor;
    private Long grandTotalMinor;
    private Long openBalanceMinor;
    private UUID shipToLocationId;
    private String paymentTermsId;
    private LocalDate poDate;
    private LocalDate expectedDeliveryDate;
    private String requestedBy;
    private String comment;
    private String approverId;
    private Instant approvalTimestamp;
    private String approvalNotes;
    private String encumbranceRef;
    private String createdBy;
    private String updatedBy;
    private Instant createdAt;
    private Instant updatedAt;
    private List<PurchaseOrderLineResponse> lines;
}
