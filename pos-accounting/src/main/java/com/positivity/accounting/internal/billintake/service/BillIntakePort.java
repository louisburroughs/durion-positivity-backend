package com.positivity.accounting.internal.billintake.service;

import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.domainevents.supplier.SupplierInvoiceReceivedV1;
import java.io.Serial;
import java.time.LocalDateTime;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The one place a new vendor bill is born (CAP:550 S25, #2518; ADR-0070 Decision 1). Every channel that can create a
 * bill goes through it: a goods receipt, an EDI invoice fact, and (from Part C) a confirmed draft. It is the only class
 * allowed to construct a {@code VendorBill} (module {@code ArchitectureTest}), so the vendor guards (S24), the duplicate
 * rule (S0, #2501) and the delivery check (G16, ruling 8) cannot be skipped by a new path.
 *
 * <p>Neither method opens a transaction a caller could not see the end of, with one exception that is documented on
 * {@link #acceptGoodsReceipt}. {@link #acceptMachineDocument} joins the caller's transaction, which must exist: the
 * EDI listener's handler transaction.
 */
public interface BillIntakePort {

    /** The {@code createdBy} of a bill or draft the supplier feed produced; also the owner of its processed marks. */
    String EDI_CREATED_BY = "supplier";

    /** The source system of the {@code accounting_event} row an EDI fact leaves. */
    String EDI_SOURCE_SYSTEM = "pos-supplier";

    /** The {@code originEventType} of a bill made from a supplier invoice fact. */
    String EDI_ORIGIN_EVENT_TYPE = "SUPPLIER_INVOICE_RECEIVED";

    /**
     * Creates the vendor bill of a goods receipt, in {@code PENDING_RECEIPT_MATCH}; nothing posts (AW37).
     *
     * <p>Not {@code @Transactional}: the work runs in a transaction this class can see the end of, which joins a
     * caller's when there is one. A replayed event id returns the bill it made. A bill that would duplicate a live one
     * (same vendor, normalised number and bill date) is refused with {@code VendorBillDuplicateException} before
     * anything is saved; when a concurrent writer commits the same key between that check and the insert, the unique
     * index refuses the insert instead and the original is read in a {@code REQUIRES_NEW} transaction, so both paths
     * give the same answer. The vendor must be in the copy (503 {@code VENDOR_REPLICATION_PENDING}) and active (422
     * {@code VENDOR_INACTIVE}).
     *
     * <p><strong>Connections.</strong> The bill's number is drawn under the tenant's counter row lock, held until the
     * transaction ends, so while it is held this method asks for no second connection (the defect #2342 removed from
     * the counter's own bootstrap). The one exception is the collision path inside a caller's transaction: reading the
     * original takes one more connection, bounded to one indexed read per refused create.
     */
    @NonNull
    VendorBillResponse acceptGoodsReceipt(@NonNull GoodsReceivedEvent event);

    /**
     * Turns a supplier invoice fact into a bill, a flagged duplicate (S0) or, when it names no vendor, a draft. Joins
     * the caller's transaction, in which the fact's processed mark is also written.
     *
     * <ul>
     *   <li>No {@code vendorId}: a {@code BillIntakeItem} on channel {@code EDI}, {@code READY_FOR_REVIEW}, the fact's
     *       values {@code READ} and its vendor {@code CHECK_THIS}; the ingestion record is keyed on the draft's id.
     *   <li>A vendor that is not in the copy yet: {@link MachineDocumentOutcome.VendorNotInCopy}, nothing written; the
     *       listener holds the fact.
     *   <li>Otherwise the S0 duplicate rule, then the bill, exactly as the listener made it before S25.
     * </ul>
     *
     * @param eventId the fact's envelope event id
     * @throws DuplicateRuleCollision when the unique index refused the insert after the check (the handler runs again)
     * @throws IngestionRecordFailure when the fact's {@code accounting_event} row could not be written
     */
    @NonNull
    MachineDocumentOutcome acceptMachineDocument(@NonNull SupplierInvoiceReceivedV1 fact, @NonNull String eventId);

    /** What became of a machine document. */
    sealed interface MachineDocumentOutcome {

        /** A bill was created, or an existing one was flagged against (S0). */
        record Bill(@NonNull UUID billId) implements MachineDocumentOutcome {}

        /** The fact named no vendor: a draft was written. */
        record Draft(@NonNull UUID intakeId, @NonNull String intakeReference) implements MachineDocumentOutcome {}

        /** The fact names a vendor accounting has not copied yet; nothing was written. */
        record VendorNotInCopy(@NonNull UUID vendorId) implements MachineDocumentOutcome {}
    }

    /**
     * The bill insert refused by the duplicate rule's unique index: a concurrent writer committed the same key after the
     * check. The listener's handler transaction rolls back and runs once more; a second one is rethrown as its cause.
     */
    final class DuplicateRuleCollision extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final UUID vendorId;
        private final String billNumber;
        private final LocalDateTime billDate;

        DuplicateRuleCollision(
                @NonNull UUID vendorId,
                @NonNull String billNumber,
                @NonNull LocalDateTime billDate,
                @NonNull DataIntegrityViolationException cause) {
            super(cause);
            this.vendorId = vendorId;
            this.billNumber = billNumber;
            this.billDate = billDate;
        }

        public @NonNull UUID vendorId() {
            return vendorId;
        }

        public @NonNull String billNumber() {
            return billNumber;
        }

        public @NonNull LocalDateTime billDate() {
            return billDate;
        }

        @Override
        public synchronized @NonNull DataIntegrityViolationException getCause() {
            return (DataIntegrityViolationException) super.getCause();
        }
    }

    /**
     * An ingestion-record failure carried out of the handler transaction past the malformed-payload catch, so the
     * listener rethrows the original exception unmarked: marking the fact processed would lose the bill with the record,
     * since both roll back together.
     */
    final class IngestionRecordFailure extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        IngestionRecordFailure(@NonNull RuntimeException cause) {
            super(cause);
        }

        @Override
        public synchronized @NonNull RuntimeException getCause() {
            return (RuntimeException) super.getCause();
        }
    }
}
