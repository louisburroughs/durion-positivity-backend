package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.VendorBillGlPosting;
import com.positivity.accounting.internal.event.LedgerReversalApplied;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.VendorBillGlPostingRepository;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Keeps a vendor bill's ledger and its status together (CAP:550 S12, #2509 review; AW42; ADR-0047): only the bill's
 * void reverses the entry its approval posted, and the void's reversal is never reversed itself.
 *
 * <ul>
 *   <li>A reversal of a bill's entry through {@code POST /v1/accounting/journal-entries/{id}/reverse} is 409 {@code
 *       AP_BILL_ENTRY_NOT_REVERSIBLE}: the bill would stay {@code APPROVED}, payable and in the Pay stage with nothing
 *       in accounts payable behind it. The caller is pointed to {@code POST /v1/accounting/vendor-bills/{billId}/void},
 *       which checks allocations, voids the bill and reverses the entry in one transaction.
 *   <li>A reversal of a void's reversal is 409 {@code AP_BILL_ENTRY_NOT_REVERSIBLE} too: it would post the bill again
 *       while the bill stays {@code VOIDED}. A voided bill is replaced by a new one.
 * </ul>
 *
 * The whole reversal rolls back with the refusal. It hears the in-process event {@code JournalEntryServiceImpl}
 * publishes after a reversal and runs in that transaction, as {@link DepositReversalReaction} does. The void binds
 * its bill over {@link #underVoid} for the length of its reversal, which is how this reaction tells it from any other.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VendorBillReversalReaction {

    /** The key the void binds its bill under for the length of its reversal. */
    private static final String VOID_RESOURCE = VendorBillReversalReaction.class.getName() + ".void";

    private final VendorBillGlPostingRepository postings;

    /** Runs {@code reversal} as the void of {@code billId}, the one route that may reverse the bill's entry. */
    static <T> T underVoid(@NonNull UUID billId, @NonNull Supplier<T> reversal) {
        TransactionSynchronizationManager.bindResource(VOID_RESOURCE, billId);
        try {
            return reversal.get();
        } finally {
            TransactionSynchronizationManager.unbindResourceIfPossible(VOID_RESOURCE);
        }
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onReversed(@NonNull LedgerReversalApplied reversed) {
        UUID entryId = reversed.originalJournalEntryId();
        postings.findByReversalJournalEntryId(entryId).ifPresent(posting -> {
            log.warn("Refused the reversal of vendor bill {} void entry {}", posting.getVendorBillId(), entryId);
            throw new VendorBillException(
                    VendorBillException.Code.AP_BILL_ENTRY_NOT_REVERSIBLE,
                    "This entry reverses a vendor bill's posting for its void and is never reversed itself; record"
                            + " the bill again instead");
        });
        postings.findByJournalEntryId(entryId)
                .filter(posting -> !isItsVoid(posting))
                .ifPresent(posting -> {
                    log.warn(
                            "Refused the reversal of vendor bill {} entry {} outside its void",
                            posting.getVendorBillId(),
                            entryId);
                    throw new VendorBillException(
                            VendorBillException.Code.AP_BILL_ENTRY_NOT_REVERSIBLE,
                            "This entry is vendor bill " + posting.getVendorBillId() + "'s posting; void the bill"
                                    + " instead: POST /v1/accounting/vendor-bills/" + posting.getVendorBillId()
                                    + "/void");
                });
    }

    private static boolean isItsVoid(VendorBillGlPosting posting) {
        return posting.getVendorBillId().equals(TransactionSynchronizationManager.getResource(VOID_RESOURCE));
    }
}
