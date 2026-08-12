package com.positivity.supplier.internal.enums;

/**
 * Persistence mirror of {@code service.model.PayloadCaptureLevel} (ADR-0050 §7): the
 * exchange-audit payload capture level of an endpoint binding.
 */
public enum PayloadCaptureLevel {
    /** Capture raw payloads with credential-header redaction only. */
    FULL,
    /**
     * Capture payloads with credential-header redaction plus a <strong>fixed</strong> set of sensitive body
     * field names.
     *
     * <p>Not per-binding configurable, despite ADR-0050 §7 describing that. The set is compiled into
     * {@code PayloadRedactor} and applies identically to every binding; there is no property, column or
     * request field that varies it. Recorded as a CAP-318 gap rather than implied here, because a level named
     * REDACTED that quietly redacts less than an operator configured would be worse than one that says what
     * it does.
     *
     * <p>It also only recognises <em>named</em> fields — XML elements and attributes, JSON fields, form
     * parameters. A positional or fixed-width vendor format has no field names to match, so a REDACTED
     * capture of one is stored substantially intact.
     */
    REDACTED,
    /** Capture no payloads; keep exchange metadata only. */
    METADATA_ONLY
}
