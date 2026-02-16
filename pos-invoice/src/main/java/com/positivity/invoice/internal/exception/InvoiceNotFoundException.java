package com.positivity.invoice.internal.exception;

import org.jspecify.annotations.NonNull;

import java.util.UUID;

public class InvoiceNotFoundException extends RuntimeException {

    public InvoiceNotFoundException(@NonNull UUID invoiceId) {
        super("Invoice not found: " + invoiceId);
    }
}
