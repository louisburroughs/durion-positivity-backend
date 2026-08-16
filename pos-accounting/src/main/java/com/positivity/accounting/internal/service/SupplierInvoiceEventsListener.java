package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.entity.Vendor;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.repository.VendorRepository;
import com.positivity.domainevents.supplier.SupplierInvoiceReceivedV1;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Creates AP vendor bills from vendor invoices fetched over EDIWheel B3.3 (CAP-321 #1227).
 *
 * <h2>Three judgments made here, and why</h2>
 *
 * The story that asked for this said posting semantics were an open question for the Accounting
 * domain. They are not open — this module already models the AP side, and the questions that
 * remained were about how a <em>supplier</em> invoice maps onto it. Those answers are recorded here
 * because they are decisions, not derivations, and the next person to read this code deserves to
 * know they were chosen rather than found.
 *
 * <p><strong>1. An unresolvable purchase-order reference is {@code PENDING_RECEIPT_MATCH}, not
 * {@code MATCH_EXCEPTION}.</strong> A vendor invoice routinely arrives before the goods receipt it
 * belongs to, so "no matching order yet" is the ordinary early state of a perfectly good invoice.
 * Filing it as an exception would put a queue of normal paperwork in front of a person whose
 * attention is the scarce resource, and the exception queue would stop meaning anything. An
 * invoice that genuinely never matches surfaces through ageing, which is what ageing is for.
 *
 * <p><strong>2. No journal entry is created on ingest.</strong> The status flow posts at approval
 * ({@code PENDING_RECEIPT_MATCH} → {@code APPROVED} → {@code PAID}), and an unapproved vendor
 * invoice is a claim rather than a liability anyone has agreed to. Posting on arrival would put
 * money in the ledger on the vendor's say-so alone, and a vendor that invoices in error would move
 * our accounts before anybody looked at it.
 *
 * <p><strong>3. The supplier's vendor profile id is the accounting vendor id.</strong> {@link
 * Vendor} states that its id is "assigned by the upstream system that owns the vendor relationship",
 * and for a vendor we transact with over EDIWheel that system is pos-supplier. The directory entry
 * is created on first sight so the AP screens can resolve a name; the alternative — inventing a
 * separate accounting vendor id and a mapping table to reconcile it — would create the very
 * ambiguity the upstream-assigns rule exists to avoid.
 *
 * <h2>One bill per invoice, however many times we are told</h2>
 *
 * Guarded twice, because the two failures are different. The event-id guard catches a redelivery of
 * the same message. The vendor-invoice-identity guard catches the same document being fetched again
 * — which happens by design, since fetch windows overlap so a failed fetch can be repeated safely.
 * Only the second would survive a rebuilt supplier database, and it is the one that stops a debt
 * being recorded twice.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.accounting.kafka", name = "enabled", havingValue = "true")
public class SupplierInvoiceEventsListener {

    /** Producing domain, per the repo-wide {@code processed_events} convention. */
    static final String OWNER = "supplier";

    private static final String ORIGIN_EVENT_TYPE = "SUPPLIER_INVOICE_RECEIVED";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final VendorBillRepository vendorBillRepository;
    private final VendorRepository vendorRepository;

    @KafkaListener(
            topics = "${pos.accounting.kafka.supplier-events-topic:supplier.events.v1}",
            groupId = "pos-accounting-supplier-events")
    @Transactional
    public void onSupplierEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable supplier event", e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping supplier event without eventId");
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            if (SupplierInvoiceReceivedV1.EVENT_TYPE.equals(eventType)) {
                apply(envelope, eventId);
            } else {
                log.debug("Ignoring supplier event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            // Rethrown so the container retries. Recording this as processed would lose a vendor
            // invoice permanently: the supplier side has already published it and will not publish
            // it again, so nothing would ever bring it back.
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed supplier invoice event eventId={}", eventId, e);
        }

        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void apply(JsonNode envelope, String eventId) {
        SupplierInvoiceReceivedV1 fact =
                objectMapper.treeToValue(envelope.path("payload"), SupplierInvoiceReceivedV1.class);

        UUID vendorId = fact.vendorProfileId();
        String billNumber = fact.vendorInvoiceNumber();

        Optional<VendorBill> existing = vendorBillRepository.findByVendorIdAndBillNumber(vendorId, billNumber);
        if (existing.isPresent()) {
            VendorBill bill = existing.get();
            BigDecimal incoming = signedTotal(fact);
            if (bill.getTotalAmount() != null && incoming.compareTo(bill.getTotalAmount()) != 0) {
                // Re-issued under the same identity for a different amount. Not overwritten: the
                // first version is what somebody may already have approved or paid against, and
                // replacing it would erase the disagreement rather than raise it.
                bill.setStatus(VendorBillStatus.MATCH_EXCEPTION);
                vendorBillRepository.save(bill);
                log.warn(
                        "Vendor invoice {} re-issued at {} against a bill of {}; flagged for review",
                        billNumber,
                        incoming,
                        bill.getTotalAmount());
            } else {
                log.debug("Vendor invoice {} already held; nothing to do", billNumber);
            }
            return;
        }

        ensureVendorDirectoryEntry(vendorId, fact.supplierRef());

        VendorBill bill = new VendorBill();
        bill.setVendorId(vendorId);
        bill.setVendorName(fact.supplierRef());
        // The vendor's own number, not one we mint. It is what an AP clerk quotes back to the
        // vendor, and a number of our own would be meaningless in that conversation.
        bill.setBillNumber(billNumber);
        bill.setBillDate(fact.invoiceDate().atStartOfDay());
        bill.setTotalAmount(signedTotal(fact));
        bill.setStatus(VendorBillStatus.PENDING_RECEIPT_MATCH);
        bill.setOriginEventId(UUID.fromString(eventId));
        bill.setOriginEventType(ORIGIN_EVENT_TYPE);
        bill.setPurchaseOrderNumber(fact.vendorOrderReference());
        bill.setCreatedBy(OWNER);
        bill.setModifiedBy(OWNER);

        vendorBillRepository.save(bill);
        log.info(
                "Created vendor bill from supplier invoice {} ({} {}) for vendor {}",
                billNumber,
                fact.totalGrossAmount(),
                fact.currency(),
                fact.supplierRef());
    }

    /**
     * The amount, signed by what the document is.
     *
     * <p>A credit note is money owed back, so it carries the opposite sign to an invoice. The sign
     * is taken from the document type rather than from the amount, because a vendor may state a
     * credit as a positive figure on a document that declares itself a credit note — and reading
     * the sign off the number would silently turn a refund into a debt.
     */
    private static BigDecimal signedTotal(SupplierInvoiceReceivedV1 fact) {
        BigDecimal total = fact.totalGrossAmount() == null ? BigDecimal.ZERO : fact.totalGrossAmount();
        BigDecimal magnitude = total.abs();
        return fact.isPayable() ? magnitude : magnitude.negate();
    }

    /**
     * Makes sure the AP vendor typeahead can resolve this vendor.
     *
     * <p>Created rather than required: an invoice arriving for a vendor the directory has never seen
     * is an ordinary first invoice, and refusing it would make the directory a gate on being paid.
     */
    private void ensureVendorDirectoryEntry(UUID vendorId, String name) {
        if (!vendorRepository.existsById(vendorId)) {
            vendorRepository.save(new Vendor(vendorId, name));
        }
    }
}
