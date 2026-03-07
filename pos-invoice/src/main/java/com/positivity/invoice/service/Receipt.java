package com.positivity.invoice.service;

import com.positivity.invoice.internal.enums.ReceiptStatus;
import lombok.Data;

import java.util.UUID;

@Data
public class Receipt {
    private UUID id;
    private UUID invoiceId;
    private UUID paymentIntentId;
    private String cashierId;
    private String reference;
    private ReceiptStatus status;
    private int reprintCount;
    private String lastReprintReason;
    private String lastReprintedBy;
}