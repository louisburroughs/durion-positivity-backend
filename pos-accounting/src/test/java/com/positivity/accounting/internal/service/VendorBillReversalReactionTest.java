package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.VendorBillGlPosting;
import com.positivity.accounting.internal.event.LedgerReversalApplied;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.VendorBillGlPostingRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Only a vendor bill's void reverses its entry, and the void is never reversed (CAP:550 S12, #2509 review, A1). The
 * ledger side, balances unchanged after a refusal, is VendorBillApprovalPostgresIT's.
 */
@DisplayName("VendorBillReversalReaction: a bill's entry is reversed by its void only (#2509 review, A1)")
class VendorBillReversalReactionTest {

    private static final UUID BILL_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a01");
    private static final UUID ENTRY_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a10");
    private static final UUID VOID_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a11");
    private static final UUID OTHER_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a12");

    private final VendorBillGlPostingRepository postings = mock();
    private final VendorBillReversalReaction reaction = new VendorBillReversalReaction(postings);

    @BeforeEach
    void posted() {
        VendorBillGlPosting posting = new VendorBillGlPosting();
        posting.setVendorBillId(BILL_ID);
        posting.setJournalEntryId(ENTRY_ID);
        posting.setReversalJournalEntryId(VOID_ID);
        when(postings.findByJournalEntryId(ENTRY_ID)).thenReturn(Optional.of(posting));
        when(postings.findByReversalJournalEntryId(VOID_ID)).thenReturn(Optional.of(posting));
        when(postings.findByJournalEntryId(OTHER_ID)).thenReturn(Optional.empty());
        when(postings.findByReversalJournalEntryId(OTHER_ID)).thenReturn(Optional.empty());
        when(postings.findByJournalEntryId(VOID_ID)).thenReturn(Optional.empty());
    }

    private static LedgerReversalApplied reversalOf(UUID entryId) {
        return new LedgerReversalApplied(
                entryId,
                UUID.randomUUID(),
                LocalDate.of(2026, 10, 3),
                List.of(),
                Set.of(),
                "controller.cfo",
                null,
                "x");
    }

    @Test
    @DisplayName("The journal-entry reversal of a bill's entry is 409 AP_BILL_ENTRY_NOT_REVERSIBLE, pointing to the"
            + " void")
    void billEntryOutsideItsVoidIsRefused() {
        assertThatThrownBy(() -> reaction.onReversed(reversalOf(ENTRY_ID)))
                .isInstanceOfSatisfying(VendorBillException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_BILL_ENTRY_NOT_REVERSIBLE);
                    assertThat(e.getMessage()).contains("/v1/accounting/vendor-bills/" + BILL_ID + "/void");
                });
    }

    @Test
    @DisplayName("The bill's own void reverses it")
    void theVoidReversesIt() {
        assertThatCode(() -> VendorBillReversalReaction.underVoid(BILL_ID, () -> {
                    reaction.onReversed(reversalOf(ENTRY_ID));
                    return null;
                }))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Another bill's void does not unlock this bill's entry")
    void anotherBillsVoidIsRefused() {
        assertThatThrownBy(() -> VendorBillReversalReaction.underVoid(UUID.randomUUID(), () -> {
                    reaction.onReversed(reversalOf(ENTRY_ID));
                    return null;
                }))
                .isInstanceOf(VendorBillException.class);
    }

    @Test
    @DisplayName("A void's reversal is never reversed, not even under a void")
    void voidReversalIsNeverReversed() {
        assertThatThrownBy(() -> reaction.onReversed(reversalOf(VOID_ID)))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_BILL_ENTRY_NOT_REVERSIBLE));
        assertThatThrownBy(() -> VendorBillReversalReaction.underVoid(BILL_ID, () -> {
                    reaction.onReversed(reversalOf(VOID_ID));
                    return null;
                }))
                .isInstanceOf(VendorBillException.class);
    }

    @Test
    @DisplayName("An entry that is no bill's passes untouched")
    void otherEntriesPass() {
        assertThatCode(() -> reaction.onReversed(reversalOf(OTHER_ID))).doesNotThrowAnyException();
    }
}
