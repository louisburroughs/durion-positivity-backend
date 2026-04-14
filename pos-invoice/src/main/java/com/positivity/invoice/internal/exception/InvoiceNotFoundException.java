package com.positivity.invoice.internal.exception;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

public class InvoiceNotFoundException extends RuntimeException {

    public InvoiceNotFoundException(@NonNull UUID invoiceId) {
        super("Invoice not found: " + invoiceId);
    }

    public InvoiceNotFoundException(@NonNull String message) {
        super(message);
    }
}
