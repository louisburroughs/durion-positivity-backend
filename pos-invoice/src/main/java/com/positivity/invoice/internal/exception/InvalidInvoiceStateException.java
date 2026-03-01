package com.positivity.invoice.internal.exception;

import com.positivity.invoice.internal.enums.InvoiceStatus;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

public class InvalidInvoiceStateException extends RuntimeException {

    public InvalidInvoiceStateException(@NonNull String message) {
        super(message);
    }

    public InvalidInvoiceStateException(
            @NonNull UUID invoiceId,
            @NonNull InvoiceStatus actual,
            @NonNull InvoiceStatus expected) {
        super("Invalid invoice state for invoice " + invoiceId + ". Expected " + expected + " but was " + actual);
    }
}
