package com.positivity.supplier.internal.service.model;

/**
 * Exchange-audit payload capture level of an endpoint binding (ADR-0050 §7). The exchange
 * metadata trail (timings, outcome, correlation id) is always kept regardless of level.
 */
public enum PayloadCaptureLevel {
    /** Capture raw request/response payloads with credential-header redaction only. */
    FULL,
    /**
     * Capture payloads with credential redaction plus the named fields of the binding's declared
     * {@link RedactionClassification}s (ADR-0050 §7 minimization — redaction configuration lives with the
     * binding).
     *
     * <p>The credential vocabulary is fixed and applies identically everywhere; what varies per binding is
     * which classifications are declared on top of it. A binding declaring none redacts credentials only.
     *
     * <p>Both halves only recognise <em>named</em> fields — XML elements and attributes, JSON fields, form
     * parameters. A positional or fixed-width vendor format has no field names to match, so a REDACTED
     * capture of one is stored substantially intact whatever the binding declares; {@code METADATA_ONLY}
     * remains the only level that guarantees no content is retained for positional families until CAP-318's
     * codec-aware redaction.
     */
    REDACTED,
    /** Capture no payloads; keep exchange metadata only. */
    METADATA_ONLY
}
