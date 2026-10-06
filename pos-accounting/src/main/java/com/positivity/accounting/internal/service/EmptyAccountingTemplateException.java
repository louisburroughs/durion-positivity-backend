package com.positivity.accounting.internal.service;

import java.io.Serial;

/**
 * The platform tenant holds no accounting template (#2526): the repeatable seed has not run, which
 * is what a database without Flyway looks like. Nothing can be provisioned from it. The
 * {@code tenant.created} listener lets it propagate, so the record retries and dead-letters; the
 * startup sweep logs it and lets the service start.
 */
public class EmptyAccountingTemplateException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 1L;

    public EmptyAccountingTemplateException() {
        super("The platform tenant holds no accounting template: R__seed_reference_accounting.sql has not run");
    }
}
