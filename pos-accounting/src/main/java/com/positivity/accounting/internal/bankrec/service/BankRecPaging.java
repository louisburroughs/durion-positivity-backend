package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** Page bounds of the bank reconciliation lists (SPEC §6: paginated, stable sort; story S2, #2301). */
final class BankRecPaging {

    static final int MAX_PAGE_SIZE = 200;

    private BankRecPaging() {}

    static PageRequest page(int page, int size, Sort sort) {
        if (page < 0) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR, "page must be >= 0", "page", "must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR,
                    "size must be between 1 and " + MAX_PAGE_SIZE,
                    "size",
                    "between 1 and " + MAX_PAGE_SIZE);
        }
        return PageRequest.of(page, size, sort);
    }
}
