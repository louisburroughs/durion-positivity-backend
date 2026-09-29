package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.dto.ReconciliationReportResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationReviewResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** The review read model and the report of a reconciliation (SPEC §4.8, §4.7 reporting; story S4, #2303). */
public interface ReconciliationReviewService {

    /** The §4.8 review: one call, no client arithmetic. */
    @NonNull
    ReconciliationReviewResponse review(@NonNull UUID reconciliationId);

    /** The report: F2's splits plus E3, the opening terms, the unexplained counts and the clearing list. */
    @NonNull
    ReconciliationReportResponse report(@NonNull UUID reconciliationId);
}
