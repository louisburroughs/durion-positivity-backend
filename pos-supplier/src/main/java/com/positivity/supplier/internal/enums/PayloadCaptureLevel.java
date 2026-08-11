package com.positivity.supplier.internal.enums;

/**
 * Persistence mirror of {@code service.model.PayloadCaptureLevel} (ADR-0050 §7): the
 * exchange-audit payload capture level of an endpoint binding.
 */
public enum PayloadCaptureLevel {
    /** Capture raw payloads with credential-header redaction only. */
    FULL,
    /** Capture payloads with credential-header and configured body-field redaction. */
    REDACTED,
    /** Capture no payloads; keep exchange metadata only. */
    METADATA_ONLY
}
