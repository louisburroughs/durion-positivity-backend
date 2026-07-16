package com.positivity.warranty.internal.service;

import com.positivity.warranty.internal.dto.SettlementCreateRequest;
import com.positivity.warranty.internal.dto.SettlementResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Settlement execution (PRD §3.6, §7 step 6): make the customer whole the moment the dealer
 * approves the claim. A claim can settle more than once (e.g. prorated credit <em>and</em> a
 * replacement workorder); the first successful settlement moves the claim
 * {@code APPROVED → SETTLED}. Settle-customer-first: open vendor reimbursements or part returns
 * never block settlement.
 */
public interface SettlementService {

    /**
     * Executes one settlement on the claim. Calls pos-invoice for credit/refund types, validates
     * the linked workorder for {@code REPLACEMENT_WORKORDER}, records {@code NO_ACTION} as-is.
     *
     * @throws com.positivity.warranty.internal.exception.WarrantyNotFoundException when the claim
     *     does not exist
     * @throws com.positivity.warranty.internal.exception.IllegalClaimStateException when the claim
     *     is not {@code APPROVED} or {@code SETTLED}
     * @throws com.positivity.warranty.internal.exception.WarrantyUnprocessableException when a
     *     referenced replacement workorder cannot be resolved
     * @throws com.positivity.warranty.internal.exception.WarrantyIntegrationException when the
     *     pos-invoice write fails; the settlement row is persisted as {@code FAILED}
     */
    @NonNull
    SettlementResponse create(@NonNull UUID claimId, @NonNull SettlementCreateRequest request);
}
