package com.positivity.supplier.internal.spi;

/**
 * Classification of one outbound attempt (ADR-0052 §5). The distinction that matters is not
 * "did it fail" but <em>whether the vendor may already have acted on the request</em>, because
 * that is what decides whether an automatic retry is safe.
 *
 * <p>Only {@link #PRE_SEND_FAILURE} is ever retried automatically. A post-send ambiguous outcome
 * must never be auto-retried for a state-creating call: the vendor may hold the order, and a
 * blind resend duplicates it. Those outcomes go to status-first reconciliation (ADR-0052 §3) or
 * {@code MANUAL_REVIEW} (§4), both of which arrive with CAP-320 — this enum is the classification
 * those flows will branch on, and it lands now so the base client never has to guess later.
 *
 * <p>Idempotent batch reads (PRICAT, stock report, invoice fetch) are the documented exception:
 * ADR-0052 §5 permits them to retry freely because they are bounded by a checkpointed window and
 * consumers deduplicate by natural identity. That is expressed per call by
 * {@code SupplierRequest.idempotent()}, never by widening the meaning of these constants.
 */
public enum ExchangeOutcome {

    /** The vendor accepted and answered: a 2xx response was received and read. */
    OK(false),

    /**
     * The request demonstrably never left this process: DNS failure, connection refused,
     * connect timeout, or an open circuit breaker. The vendor cannot know about it, so an
     * automatic retry is safe (ADR-0052 §5, §3 "safe re-dispatch").
     */
    PRE_SEND_FAILURE(true),

    /**
     * The request body was (or may have been) transmitted and the result is unknown: read
     * timeout after send, or a 5xx received after the body went out. <strong>Never auto-retried
     * for state-creating calls</strong> — the vendor may already hold the order.
     */
    POST_SEND_AMBIGUOUS(false),

    /**
     * The vendor answered with a definitive 4xx rejection. The request reached the vendor and
     * was refused on its merits; retrying reproduces the same rejection, so the rejection is
     * surfaced instead (ADR-0052 §5).
     */
    DEFINITIVE_REJECTION(false),

    /**
     * The attempt failed before any network I/O: an unresolvable secret reference, a missing
     * account mapping, an unbound capability. A configuration state, never a transport failure
     * (ADR-0050 §3/§4/§5).
     */
    CONFIGURATION_ERROR(false);

    private final boolean preSendRetryable;

    ExchangeOutcome(boolean preSendRetryable) {
        this.preSendRetryable = preSendRetryable;
    }

    /**
     * Whether an automatic retry of this attempt is safe without vendor-side reconciliation.
     *
     * @return {@code true} only for {@link #PRE_SEND_FAILURE}
     */
    public boolean isPreSendRetryable() {
        return preSendRetryable;
    }

    /** Whether the attempt succeeded. */
    public boolean isSuccess() {
        return this == OK;
    }
}
