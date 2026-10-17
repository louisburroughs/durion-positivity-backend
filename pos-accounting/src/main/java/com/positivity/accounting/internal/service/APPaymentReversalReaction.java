package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.event.LedgerReversalApplied;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.APPaymentRepository;
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
 * Keeps an AP payment's ledger and its status together (#2642, AW42; the {@link VendorBillReversalReaction} pattern):
 * only the payment's return reverses the entry the payment posted, and the return's mirror is never reversed itself.
 *
 * <ul>
 *   <li>A reversal of a payment's entry through {@code POST /v1/accounting/journal-entries/{id}/reverse} is 409 {@code
 *       AP_PAYMENT_ENTRY_NOT_REVERSIBLE}: the payment would stay {@code GL_POSTED} with its bills paid and nothing in
 *       the bank behind it. The caller is pointed to {@code POST /v1/accounting/ap/payments/{paymentId}/record-return},
 *       which reverses the entry, reopens the allocations and records the return in one transaction.
 *   <li>A reversal of the mirror is 409 too: it would pay the bills again while the payment stays {@code RETURNED}. The
 *       bills are paid again by a new pay command with a new paymentRef.
 * </ul>
 *
 * A {@code CASH} payment shares the register session's close entry and is not guarded here (a drawer payout is
 * corrected by a drawer movement). The return binds its payment over {@link #underReturn} for the length of its
 * reversal, which is how this reaction tells it from any other.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class APPaymentReversalReaction {

    private static final String RETURN_RESOURCE = APPaymentReversalReaction.class.getName() + ".return";

    private final APPaymentRepository payments;

    /** Runs {@code reversal} as the return of {@code paymentId}, the one route that may reverse the payment's entry. */
    static <T> T underReturn(@NonNull UUID paymentId, @NonNull Supplier<T> reversal) {
        TransactionSynchronizationManager.bindResource(RETURN_RESOURCE, paymentId);
        try {
            return reversal.get();
        } finally {
            TransactionSynchronizationManager.unbindResourceIfPossible(RETURN_RESOURCE);
        }
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onReversed(@NonNull LedgerReversalApplied reversed) {
        UUID entryId = reversed.originalJournalEntryId();
        payments.findByReturnJournalEntryId(entryId).ifPresent(payment -> {
            log.warn("Refused the reversal of AP payment {} return entry {}", payment.getPaymentRef(), entryId);
            throw new VendorBillException(
                    VendorBillException.Code.AP_PAYMENT_ENTRY_NOT_REVERSIBLE,
                    "This entry is the return of AP payment " + payment.getPaymentRef()
                            + " and is never reversed itself; pay the bills again with a new paymentRef",
                    java.util.List.of(),
                    "Pay the vendor's bills again with executePayment and a new paymentRef");
        });
        payments.findByGlJournalEntry_JournalEntryId(entryId)
                .filter(payment -> payment.getPaymentMethod() != PaymentMethod.CASH)
                .filter(payment -> !isItsReturn(payment.getPaymentId()))
                .ifPresent(payment -> {
                    log.warn(
                            "Refused the reversal of AP payment {} entry {} outside its return",
                            payment.getPaymentRef(),
                            entryId);
                    throw new VendorBillException(
                            VendorBillException.Code.AP_PAYMENT_ENTRY_NOT_REVERSIBLE,
                            "This entry is AP payment " + payment.getPaymentRef()
                                    + "'s posting; record the charge's return instead",
                            java.util.List.of(),
                            "Record the return: POST /v1/accounting/ap/payments/" + payment.getPaymentId()
                                    + "/record-return");
                });
    }

    private static boolean isItsReturn(UUID paymentId) {
        return paymentId.equals(TransactionSynchronizationManager.getResource(RETURN_RESOURCE));
    }
}
