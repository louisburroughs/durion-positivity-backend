package com.positivity.accounting.internal.billintake.service;

import com.positivity.accounting.internal.entity.SupplierInvoiceHold;
import com.positivity.accounting.internal.repository.SupplierInvoiceHoldRepository;
import com.positivity.domainevents.supplier.SupplierInvoiceReceivedV1;
import com.positivity.tenancy.TenantIterator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Converts the open {@code VENDOR_ID_MISSING} holds into bill drafts (CAP:550 S25, #2518; the reason is retired).
 *
 * <p>Before S25 an EDI invoice fact that named no vendor was held, waiting for a person who had nothing to act on. Now
 * such a fact becomes a {@code BillIntakeItem} on channel {@code EDI} at once ({@link
 * BillIntakePort#acceptMachineDocument}); this runner gives the facts already held the same end. Each hold is
 * converted in a transaction of its own, per tenant ({@link TenantIterator}), from the envelope the hold kept: the
 * draft and its ingestion record are written, and the hold is marked released with {@code releasedBillId} null and
 * {@code releasedIntakeId} naming the draft.
 *
 * <p><b>Idempotent.</b> The hold is read under its row lock and its {@code released_at} re-read there, so two instances
 * starting together convert it once and a second run finds nothing open. A conversion that fails is logged and left for
 * the next start; one hold's failure stops neither the others nor the service. The reason's enum constant stays, so
 * the converted rows still read.
 *
 * <p>{@link VendorlessHoldConversionRunner} runs it at startup; tests call {@link #convertOpenHolds} directly.
 */
@Slf4j
@Component
public class VendorlessHoldConverter {

    private final TenantIterator tenantIterator;
    private final SupplierInvoiceHoldRepository holds;
    private final BillIntakePort billIntake;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public VendorlessHoldConverter(
            TenantIterator tenantIterator,
            SupplierInvoiceHoldRepository holds,
            BillIntakePort billIntake,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.tenantIterator = tenantIterator;
        this.holds = holds;
        this.billIntake = billIntake;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Converts every active tenant's open holds; returns how many drafts were written. */
    public int convertAll() {
        AtomicInteger converted = new AtomicInteger();
        tenantIterator.forEachActiveTenant(tenantId -> converted.addAndGet(convertOpenHolds()));
        return converted.get();
    }

    /** Converts the bound tenant's open holds; returns how many drafts were written. */
    public int convertOpenHolds() {
        List<UUID> open = transaction.execute(_ -> holds
                .findByReasonAndReleasedAtIsNullOrderByReceivedAtAscHoldIdAsc(
                        SupplierInvoiceHold.Reason.VENDOR_ID_MISSING)
                .stream()
                .map(SupplierInvoiceHold::getHoldId)
                .toList());
        if (open == null) {
            return 0;
        }
        int converted = 0;
        for (UUID holdId : open) {
            try {
                if (Boolean.TRUE.equals(transaction.execute(_ -> convert(holdId)))) {
                    converted++;
                }
            } catch (RuntimeException e) {
                // A failure leaves the hold open for the next start; its payload may quote the document: class only.
                log.warn(
                        "Converting held supplier invoice holdId={} into a draft failed ({}); it stays held",
                        holdId,
                        e.getClass().getSimpleName());
            }
        }
        return converted;
    }

    private boolean convert(@NonNull UUID holdId) {
        SupplierInvoiceHold hold = holds.lockByHoldId(holdId).orElse(null);
        if (hold == null || hold.getReleasedAt() != null) {
            return false;
        }
        JsonNode envelope = objectMapper.readTree(hold.getPayload());
        SupplierInvoiceReceivedV1 fact =
                objectMapper.treeToValue(envelope.path("payload"), SupplierInvoiceReceivedV1.class);
        BillIntakePort.MachineDocumentOutcome outcome =
                billIntake.acceptMachineDocument(fact, hold.getEventId().toString());
        switch (outcome) {
            case BillIntakePort.MachineDocumentOutcome.Draft draft -> {
                hold.setReleasedAt(Instant.now(clock));
                hold.setReleasedIntakeId(draft.intakeId());
                holds.save(hold);
                log.info(
                        "Converted held supplier invoice {} eventId={} into draft {}",
                        hold.getSupplierInvoiceRef(),
                        hold.getEventId(),
                        draft.intakeReference());
                return true;
            }
            case BillIntakePort.MachineDocumentOutcome.Bill bill -> {
                // A held fact that names a vendor after all: it became (or flagged) a bill, as a release would.
                hold.setReleasedAt(Instant.now(clock));
                hold.setReleasedBillId(bill.billId());
                holds.save(hold);
                return true;
            }
            case BillIntakePort.MachineDocumentOutcome.VendorNotInCopy _ -> {
                return false;
            }
        }
    }
}
