package com.positivity.accounting.internal.exception;

/**
 * Thrown when a bank statement CSV import ({@link com.positivity.accounting.internal.service.BankStatementCsvParser})
 * cannot be parsed: empty content, no statement lines, or a malformed row
 * (bad column count, date, or amount). Carries the offending line number in
 * its message where applicable. Maps to HTTP 400 (VALIDATION_ERROR).
 */
public class BankStatementParseException extends RuntimeException {

    public BankStatementParseException(String message) {
        super(message);
    }
}
