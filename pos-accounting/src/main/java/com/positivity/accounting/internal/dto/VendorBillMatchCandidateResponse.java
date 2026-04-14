package com.positivity.accounting.internal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * Response DTO for a vendor bill match candidate.
 * Presents candidate details to the UI for manual selection during ambiguous
 * matching.
 */
@Value
@Builder
public class VendorBillMatchCandidateResponse {

    /** Candidate record ID (used for selection). */
    UUID candidateId;

    /** The invoice event that triggered the ambiguous match. */
    UUID invoiceEventId;

    /** The candidate vendor bill ID. */
    UUID vendorBillId;

    /** Vendor ID. */
    UUID vendorId;

    /** Bill number for display. */
    String billNumber;

    /** Bill total amount. */
    BigDecimal billTotalAmount;

    /** Composite match score (0–100). */
    int matchScore;

    /** Score breakdown details. */
    String scoreBreakdown;

    /** Whether this candidate has been resolved. */
    boolean resolved;

    /** Whether this candidate was the selected one. */
    boolean selected;

    /** When the candidate record was created. */
    Instant createdAt;
}
