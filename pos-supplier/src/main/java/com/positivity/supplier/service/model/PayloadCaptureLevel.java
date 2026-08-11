package com.positivity.supplier.service.model;

/**
 * Exchange-audit payload capture level of an endpoint binding (ADR-0050 §7). The exchange
 * metadata trail (timings, outcome, correlation id) is always kept regardless of level.
 */
public enum PayloadCaptureLevel {
    /** Capture raw request/response payloads with credential-header redaction only. */
    FULL,
    /** Capture payloads with credential-header and configured body-field redaction. */
    REDACTED,
    /** Capture no payloads; keep exchange metadata only. */
    METADATA_ONLY
}
