package com.positivity.invoice.internal.dto;

import com.positivity.invoice.internal.enums.ReceiptStatus;
import lombok.Data;

import java.util.UUID;

@Data
public class ReceiptResponse {
    private UUID receiptId;
    private String reference;
    private ReceiptStatus status;
}