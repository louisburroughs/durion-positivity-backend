package com.positivity.accounting.internal.exception;

import org.jspecify.annotations.NonNull;

/**
 * A credit memo cannot reverse its tax by type (CAP:550 S32d item 11, AW50): the tenant posts output tax by tax type,
 * and the original invoice's tax rows carry no type for some of its tax, or a type has no mapped {@code
 * SALES_TAX_PAYABLE_<taxType>} key. 422 {@value #CODE}: the state of the invoice's tax rows and the tenant's keys, not
 * the request's shape (ADR-0017 §2). Nothing is stored; no default account is used and no type is inferred.
 */
public class TaxTypeMissingException extends RuntimeException {

    public static final String CODE = "TAX_TYPE_MISSING";

    public TaxTypeMissingException(@NonNull String message) {
        super(message);
    }
}
