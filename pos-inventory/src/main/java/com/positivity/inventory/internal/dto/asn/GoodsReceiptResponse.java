package com.positivity.inventory.internal.dto.asn;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GoodsReceiptResponse {

    private UUID receiptId;
    private String receiptNumber;
    private UUID poId;
    private UUID asnId;
    private UUID locationId;
    private Long totalAccruedAmountMinor;
    private String createdBy;
    private Instant createdAt;
    private List<GoodsReceiptLineResponse> lines;
}
