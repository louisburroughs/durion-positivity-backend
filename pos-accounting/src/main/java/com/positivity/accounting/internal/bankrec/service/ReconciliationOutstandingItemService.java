package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.dto.OutstandingItemJustificationRequest;
import com.positivity.accounting.internal.bankrec.dto.OutstandingItemReasonRequest;
import com.positivity.accounting.internal.bankrec.dto.OutstandingItemRegisterRequest;
import com.positivity.accounting.internal.bankrec.dto.OutstandingItemResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Outstanding (timing) items (SPEC-manual-bank-reconciliation §3.6, §5.4; story S4, #2303): register, release,
 * reaffirm an aged {@code OTHER_LEDGER_TIMING} item, and clear an item whose other side appeared during an
 * acknowledged gap. Nothing here posts (O3).
 */
public interface ReconciliationOutstandingItemService {

    @NonNull
    OutstandingItemResponse register(@NonNull UUID reconciliationId, @NonNull OutstandingItemRegisterRequest request);

    @NonNull
    OutstandingItemResponse release(
            @NonNull UUID reconciliationId, @NonNull UUID itemId, @NonNull OutstandingItemReasonRequest request);

    @NonNull
    OutstandingItemResponse reaffirm(
            @NonNull UUID reconciliationId, @NonNull UUID itemId, @NonNull OutstandingItemJustificationRequest request);

    @NonNull
    OutstandingItemResponse clearInGap(
            @NonNull UUID reconciliationId, @NonNull UUID itemId, @NonNull OutstandingItemJustificationRequest request);
}
