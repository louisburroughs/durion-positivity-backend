package com.positivity.invoice.internal.dto;

import com.positivity.invoice.internal.enums.ReceiptStatus;
import java.util.UUID;
import lombok.Data;

@Data
public class ReceiptResponse {
    private UUID receiptId;
    private String reference;
    private ReceiptStatus status;
}
