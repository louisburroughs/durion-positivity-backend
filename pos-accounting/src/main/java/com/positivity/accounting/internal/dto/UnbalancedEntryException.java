package com.positivity.accounting.internal.dto;

/**
 * Thrown when a journal entry fails balance validation (total debits ≠ total
 * credits, or the entry has no lines at all). Maps to HTTP 422
 * (UNBALANCED_ENTRY) — a domain-policy violation on an otherwise
 * well-formed entry, not a malformed request, so this deliberately extends
 * {@link RuntimeException} rather than {@link IllegalArgumentException}.
 */
public class UnbalancedEntryException extends RuntimeException {

    public UnbalancedEntryException(String message) {
        super(message);
    }
}
