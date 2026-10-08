package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.ApApprovalPolicyRequest;
import com.positivity.accounting.internal.dto.ApApprovalPolicyResponse;
import org.jspecify.annotations.NonNull;

/**
 * The AP approval policy (CAP:550 S13, #2510; SPEC-accounting-workspace §4.3, §5.5; AW4-AW6, AW33): the clerk and
 * automatic approval limits, the two separation-of-duties exception switches and the default AP terms, with their
 * change history. Gated by {@code accounting:ap_approval_policy:manage}.
 */
public interface ApApprovalPolicyService {

    /** Default page size of the history. */
    int DEFAULT_HISTORY_SIZE = 20;

    /** Largest page size of the history. */
    int MAX_HISTORY_SIZE = 100;

    /** The effective policy and one page of its history, newest first. */
    @NonNull
    ApApprovalPolicyResponse get(int historyPage, int historySize);

    /**
     * Changes the settings the request gives (a missing one is unchanged), writing and auditing only those whose
     * effective value changes, one {@code AP_APPROVAL_POLICY_SET} row each. Idempotent on {@code requestId}: a request
     * id already recorded writes nothing, and so does a body equal to the stored values. Returns the GET body.
     */
    @NonNull
    ApApprovalPolicyResponse set(@NonNull ApApprovalPolicyRequest request);
}
