package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.event.LedgerReversalApplied;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Only a payment's return reverses its entry, and the mirror is never reversed (#2642). */
@DisplayName("APPaymentReversalReaction: a payment's entry is reversed by its return only (#2642)")
class APPaymentReversalReactionTest {

    private static final UUID PAYMENT_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f7001");
    private static final UUID ENTRY_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f7010");
    private static final UUID MIRROR_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f7011");
    private static final UUID OTHER_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f7012");
    private static final UUID CASH_ENTRY_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f7013");

    private final APPaymentRepository payments = mock();
    private final APPaymentReversalReaction reaction = new APPaymentReversalReaction(payments);

    @BeforeEach
    void posted() {
        APPayment payment = new APPayment(PAYMENT_ID);
        payment.setPaymentRef("PAY-412");
        payment.setPaymentMethod(PaymentMethod.ACH);
        payment.setGlJournalEntry(new JournalEntry(ENTRY_ID));
        payment.setReturnJournalEntryId(MIRROR_ID);
        APPayment cash = new APPayment(UUID.randomUUID());
        cash.setPaymentRef("COD-1");
        cash.setPaymentMethod(PaymentMethod.CASH);
        cash.setGlJournalEntry(new JournalEntry(CASH_ENTRY_ID));
        when(payments.findByGlJournalEntry_JournalEntryId(ENTRY_ID)).thenReturn(Optional.of(payment));
        when(payments.findByReturnJournalEntryId(MIRROR_ID)).thenReturn(Optional.of(payment));
        when(payments.findByGlJournalEntry_JournalEntryId(CASH_ENTRY_ID)).thenReturn(Optional.of(cash));
        when(payments.findByGlJournalEntry_JournalEntryId(OTHER_ID)).thenReturn(Optional.empty());
        when(payments.findByReturnJournalEntryId(OTHER_ID)).thenReturn(Optional.empty());
        when(payments.findByReturnJournalEntryId(ENTRY_ID)).thenReturn(Optional.empty());
        when(payments.findByReturnJournalEntryId(CASH_ENTRY_ID)).thenReturn(Optional.empty());
        when(payments.findByGlJournalEntry_JournalEntryId(MIRROR_ID)).thenReturn(Optional.empty());
    }

    private static LedgerReversalApplied reversalOf(UUID entryId) {
        return new LedgerReversalApplied(
                entryId, UUID.randomUUID(), LocalDate.of(2026, 10, 15), List.of(), Set.of(), "controller.cfo", null, "x");
    }

    @Test
    @DisplayName("a manual reversal of the payment's entry is 409 AP_PAYMENT_ENTRY_NOT_REVERSIBLE, pointing at record-return")
    void paymentEntryOutsideItsReturn() {
        assertThatThrownBy(() -> reaction.onReversed(reversalOf(ENTRY_ID)))
                .isInstanceOfSatisfying(VendorBillException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_PAYMENT_ENTRY_NOT_REVERSIBLE);
                    assertThat(e.getNextAction()).contains("/ap/payments/" + PAYMENT_ID + "/record-return");
                });
    }

    @Test
    @DisplayName("under the payment's return the same reversal passes")
    void paymentEntryUnderItsReturn() {
        assertThatCode(() -> APPaymentReversalReaction.underReturn(PAYMENT_ID, () -> {
                    reaction.onReversed(reversalOf(ENTRY_ID));
                    return null;
                }))
                .doesNotThrowAnyException();
        // The binding does not outlive the return.
        assertThatThrownBy(() -> reaction.onReversed(reversalOf(ENTRY_ID))).isInstanceOf(VendorBillException.class);
    }

    @Test
    @DisplayName("the mirror is never reversed, under a return or not")
    void mirrorNeverReversed() {
        assertThatThrownBy(() -> APPaymentReversalReaction.underReturn(PAYMENT_ID, () -> {
                    reaction.onReversed(reversalOf(MIRROR_ID));
                    return null;
                }))
                .isInstanceOfSatisfying(VendorBillException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_PAYMENT_ENTRY_NOT_REVERSIBLE);
                    assertThat(e.getNextAction()).contains("new paymentRef");
                });
    }

    @Test
    @DisplayName("a CASH payment's session-close entry and an unrelated entry are not guarded here")
    void cashAndUnrelatedPass() {
        assertThatCode(() -> reaction.onReversed(reversalOf(CASH_ENTRY_ID))).doesNotThrowAnyException();
        assertThatCode(() -> reaction.onReversed(reversalOf(OTHER_ID))).doesNotThrowAnyException();
    }
}
