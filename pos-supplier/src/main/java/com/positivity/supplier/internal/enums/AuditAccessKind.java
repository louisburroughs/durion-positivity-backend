package com.positivity.supplier.internal.enums;

/**
 * How a caller reached exchange-audit content (ADR-0050 §7).
 *
 * <p>Only payload reads are recorded, and the {@code chk_saccess_kind} constraint pins that in the
 * schema. Two things deliberately stay out:
 *
 * <ul>
 *   <li><strong>Metadata listings.</strong> Listing exchanges discloses no commercial content, so
 *       recording it would bury the reads that matter in noise.
 *   <li><strong>Denied reads.</strong> A 403 is not a payload read — nothing was disclosed. Recording
 *       denials here would blur both the meaning of the table and its retention story; denial
 *       visibility belongs to the event mechanism, which sees every request including the rejected
 *       ones.
 * </ul>
 */
public enum AuditAccessKind {
    /** The caller retrieved stored payload content of one exchange. */
    PAYLOAD_READ
}
