package com.positivity.invoice.service;

import com.positivity.invoice.internal.enums.ReceiptDeliveryStatus;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * Story #7 Receipt Generation.
 */
public interface ReceiptService {

    @NonNull
    Receipt generateReceipt(@NonNull UUID invoiceId, @NonNull UUID paymentIntentId,
            @NonNull String terminalId,
            @NonNull String templateId, @NonNull String templateVersion);

    void recordPrintDelivery(@NonNull UUID receiptId, @NonNull ReceiptDeliveryStatus status);

    void sendEmailReceipt(@NonNull UUID receiptId, @NonNull String emailAddress,
            @NonNull ReceiptDeliveryStatus status);

    @NonNull
    Receipt reprintReceipt(@NonNull UUID receiptId, @NonNull String reason);
}
