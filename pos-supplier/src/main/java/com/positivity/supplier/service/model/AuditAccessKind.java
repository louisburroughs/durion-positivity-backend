package com.positivity.supplier.service.model;

/**
 * How a caller reached exchange-audit content (ADR-0050 §7).
 *
 * <p>Only payload reads are recorded. Metadata listings are not — they disclose no commercial content —
 * and neither are denied reads: a 403 disclosed nothing, so recording it would blur both the meaning of
 * the access trail and its retention story.
 */
public enum AuditAccessKind {
    /** The caller retrieved stored payload content of one exchange. */
    PAYLOAD_READ
}
