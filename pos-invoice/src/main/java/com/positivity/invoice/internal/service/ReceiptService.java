package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.dto.ReceiptViewResponse;
import com.positivity.invoice.internal.enums.ReceiptDeliveryStatus;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Story #7 Receipt Generation.
 */
public interface ReceiptService {

    @NonNull
    Receipt generateReceipt(
            @NonNull UUID invoiceId,
            @NonNull UUID paymentIntentId,
            @NonNull String terminalId,
            @NonNull String templateId,
            @NonNull String templateVersion);

    /**
     * Full read view of a single receipt (issue #2214).
     *
     * @throws com.positivity.invoice.internal.exception.ReceiptNotFoundException when the
     *     receipt does not exist, or exists but does not belong to {@code invoiceId} — the two
     *     cases are not distinguished, so a caller cannot use this to discover other invoices'
     *     receipt ids.
     */
    @NonNull
    ReceiptViewResponse getReceipt(@NonNull UUID invoiceId, @NonNull UUID receiptId);

    void recordPrintDelivery(@NonNull UUID invoiceId, @NonNull UUID receiptId, @NonNull ReceiptDeliveryStatus status);

    void sendEmailReceipt(
            @NonNull UUID invoiceId,
            @NonNull UUID receiptId,
            @NonNull String emailAddress,
            @NonNull ReceiptDeliveryStatus status);

    @NonNull
    Receipt reprintReceipt(@NonNull UUID receiptId, @NonNull String reason);
}
