package com.positivity.accounting.internal.billintake.service;

import com.positivity.accounting.internal.billintake.entity.BillIntakeItem;
import com.positivity.accounting.internal.billintake.enums.BillFieldState;
import com.positivity.accounting.internal.billintake.enums.BillIntakeChannel;
import com.positivity.accounting.internal.billintake.enums.BillIntakeStatus;
import com.positivity.accounting.internal.billintake.enums.BillVendorResolution;
import com.positivity.accounting.internal.billintake.repository.BillIntakeItemRepository;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.entity.AccountingSequence;
import com.positivity.accounting.internal.entity.ExtSupplierVendor;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillLine;
import com.positivity.accounting.internal.entity.VendorBillReissue;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.repository.ExtSupplierVendorRepository;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import com.positivity.accounting.internal.repository.VendorBillReissueRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.service.AccountingCalendarZoneResolver;
import com.positivity.accounting.internal.service.AccountingSequenceLocker;
import com.positivity.accounting.internal.service.FactPostingOutcome;
import com.positivity.accounting.internal.service.KafkaFactIngestionRecorder;
import com.positivity.accounting.internal.service.SupplierVendorCopies;
import com.positivity.accounting.internal.service.VendorBillDuplicateGuard;
import com.positivity.accounting.internal.service.VendorBillLocks;
import com.positivity.accounting.internal.service.VendorBillReader;
import com.positivity.accounting.internal.service.VendorBillStatedTax;
import com.positivity.accounting.internal.service.VendorBillTotals;
import com.positivity.domainevents.supplier.SupplierInvoiceLine;
import com.positivity.domainevents.supplier.SupplierInvoiceReceivedV1;
import com.positivity.domainevents.supplier.SupplierInvoiceTax;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.tenancy.kafka.RetryableConsumerFailures;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.Serial;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link BillIntakePort}: the only class that constructs a {@code VendorBill} (module {@code ArchitectureTest}).
 *
 * <p>The two creation blocks that used to sit in {@code VendorBillServiceImpl} (goods receipt) and {@code
 * SupplierEventsListener} (EDI invoice) moved here with their behaviour, transaction shape and ingestion-record rules
 * unchanged: the S24 vendor guards, the S0 duplicate rule with its {@code LostDuplicateRace} and {@link
 * BillIntakePort.DuplicateRuleCollision} handling, the flag-never-throw rule for an EDI duplicate, the signed totals,
 * the currency hold, the stated tax by type and the {@code accounting_event} row.
 *
 * <p>What is new in S25 Part A: an EDI fact that names no vendor becomes a {@link BillIntakeItem} (channel {@code
 * EDI}) instead of a {@code VENDOR_ID_MISSING} hold, and a goods receipt may name its purchase order and delivery
 * reference (ruling 8 of #2518), kept on the bill for the delivery duplicate check ({@link
 * VendorBillDuplicateGuard#findDeliveryOriginal}).
 */
@Slf4j
@Service
public class BillIntakePortImpl implements BillIntakePort {

    private static final String SYSTEM_USER = "SYSTEM";

    /** Prefix of the per-tenant {@code accounting_sequence} scope that numbers goods-receipt bills. */
    static final String BILL_NUMBER_SCOPE_PREFIX = "BILL-";

    private final Clock clock;
    private final VendorBillRepository billRepository;
    private final VendorBillLineRepository billLineRepository;
    private final BillIntakeItemRepository intakeItems;
    private final ExtSupplierVendorRepository vendorCopy;
    private final SupplierVendorCopies vendorCopies;
    private final VendorBillDuplicateGuard duplicateGuard;
    private final VendorBillLocks locks;
    private final VendorBillReissueRepository reissueRepository;
    private final VendorBillStatedTax statedTax;
    private final VendorBillReader reader;
    private final LedgerCurrency ledgerCurrency;
    private final KafkaFactIngestionRecorder ingestionRecorder;
    private final AccountingSequenceLocker sequenceLocker;
    private final AccountingCalendarZoneResolver zoneResolver;
    private final BillIntakeReferences references;
    private final @Nullable MeterRegistry meterRegistry;

    /**
     * The goods-receipt create, in a transaction this class can see the end of (#2501): the original of a bill that lost
     * a race under {@code uq_vendor_bill_duplicate_rule} is read after this template has returned. By then the failed
     * transaction has rolled back when this class began it, and is marked rollback-only when it joined a caller's.
     */
    private final TransactionTemplate goodsReceiptTransaction;

    public BillIntakePortImpl(
            Clock clock,
            VendorBillRepository billRepository,
            VendorBillLineRepository billLineRepository,
            BillIntakeItemRepository intakeItems,
            ExtSupplierVendorRepository vendorCopy,
            SupplierVendorCopies vendorCopies,
            VendorBillDuplicateGuard duplicateGuard,
            VendorBillLocks locks,
            VendorBillReissueRepository reissueRepository,
            VendorBillStatedTax statedTax,
            VendorBillReader reader,
            LedgerCurrency ledgerCurrency,
            KafkaFactIngestionRecorder ingestionRecorder,
            AccountingSequenceLocker sequenceLocker,
            AccountingCalendarZoneResolver zoneResolver,
            ObjectProvider<MeterRegistry> meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.billRepository = billRepository;
        this.billLineRepository = billLineRepository;
        this.intakeItems = intakeItems;
        this.vendorCopy = vendorCopy;
        this.vendorCopies = vendorCopies;
        this.duplicateGuard = duplicateGuard;
        this.locks = locks;
        this.reissueRepository = reissueRepository;
        this.statedTax = statedTax;
        this.reader = reader;
        this.ledgerCurrency = ledgerCurrency;
        this.ingestionRecorder = ingestionRecorder;
        this.sequenceLocker = sequenceLocker;
        this.zoneResolver = zoneResolver;
        this.references = new BillIntakeReferences(sequenceLocker, zoneResolver);
        this.meterRegistry = meterRegistry.getIfAvailable();
        this.goodsReceiptTransaction = new TransactionTemplate(transactionManager);
    }

    private String getCurrentUser() {
        return SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_USER);
    }

    // ---- goods receipt -------------------------------------------------------------------------------------

    @Override
    public @NonNull VendorBillResponse acceptGoodsReceipt(@NonNull GoodsReceivedEvent event) {
        try {
            return Objects.requireNonNull(goodsReceiptTransaction.execute(_ -> createFromGoodsReceipt(event)));
        } catch (LostDuplicateRace lost) {
            VendorBill original = duplicateGuard
                    .findOriginalAfterCollision(event.getVendorId(), lost.billNumber, lost.billDate)
                    .orElseThrow(lost::getCause);
            throw duplicateGuard.refusal(
                    VendorBillDuplicateGuard.Channel.GOODS_RECEIPT,
                    event.getVendorId(),
                    lost.billNumber,
                    lost.billDate,
                    original);
        }
    }

    /**
     * An insert refused by {@code uq_vendor_bill_duplicate_rule}: carries what is needed to find the original out of the
     * failed transaction.
     */
    private static final class LostDuplicateRace extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String billNumber;
        private final LocalDateTime billDate;

        LostDuplicateRace(String billNumber, LocalDateTime billDate, DataIntegrityViolationException cause) {
            super(cause);
            this.billNumber = billNumber;
            this.billDate = billDate;
        }

        @Override
        public synchronized @NonNull DataIntegrityViolationException getCause() {
            return (DataIntegrityViolationException) super.getCause();
        }
    }

    private @NonNull VendorBillResponse createFromGoodsReceipt(@NonNull GoodsReceivedEvent event) {
        log.info(
                "Processing GoodsReceivedEvent | eventId={} | vendorId={} | poId={}",
                event.getEventId(),
                event.getVendorId(),
                event.getPurchaseOrderId());

        String currentUser = getCurrentUser();

        // Step 1: Idempotency check
        Optional<VendorBill> existingBill = billRepository.findByOriginEventId(event.getEventId());
        if (existingBill.isPresent()) {
            log.warn("Duplicate GoodsReceivedEvent ignored | eventId={}", event.getEventId());
            return reader.read(existingBill.get());
        }

        // Step 1b: The vendor (CAP:550 S24, #2517): a pos-supplier vendor in the copy (503 VENDOR_REPLICATION_PENDING)
        // that is ACTIVE (422 VENDOR_INACTIVE). The bill keeps the copy's display name, never the caller's.
        ExtSupplierVendor vendor = vendorCopies.requireForNewBusiness(event.getVendorId(), "A bill");

        // Step 2: Create vendor bill
        VendorBill bill = new VendorBill();
        bill.setVendorId(event.getVendorId());
        bill.setVendorName(vendor.getDisplayName());
        bill.setBillDate(event.getReceivedDate());
        bill.setStatus(VendorBillStatus.PENDING_RECEIPT_MATCH);
        bill.setOriginEventId(event.getEventId());
        bill.setOriginEventType("GOODS_RECEIVED");
        bill.setPurchaseOrderId(event.getPurchaseOrderId()); // Store PO reference
        // Ruling 8 of #2518 (G16): the order's number and the vendor's delivery reference, when the receipt names them;
        // a later bill for the same delivery is matched against this one.
        bill.setPurchaseOrderNumber(event.getPurchaseOrderNumber());
        bill.setDeliveryReference(event.getDeliveryReference());
        bill.setCreatedBy(currentUser);
        bill.setModifiedBy(currentUser);

        // Step 3: Calculate total amount from line items
        BigDecimal totalAmount = event.getLineItems().stream()
                .map(line -> line.getQuantity().multiply(line.getUnitPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        bill.setTotalAmount(totalAmount);

        // The number, as late as it can be drawn: from here to the end of this transaction the
        // tenant's counter row is locked, and nothing below asks for a second connection.
        bill.setBillNumber(generateBillNumber(event.getVendorId()));

        // Step 4: The duplicate rule (#2501), then the insert. Flushed here so the unique index
        // answers before the lines or the GL posting event see the bill.
        duplicateGuard.refuseIfDuplicate(
                VendorBillDuplicateGuard.Channel.GOODS_RECEIPT,
                bill.getVendorId(),
                bill.getBillNumber(),
                bill.getBillDate(),
                null);
        VendorBill savedBill;
        try {
            savedBill = billRepository.saveAndFlush(bill);
        } catch (DataIntegrityViolationException e) {
            if (VendorBillDuplicateGuard.isDuplicateRuleViolation(e)) {
                throw new LostDuplicateRace(bill.getBillNumber(), bill.getBillDate(), e);
            }
            throw e;
        }

        // Step 5: Save line items for three-way matching
        int lineNumber = 1;
        for (GoodsReceivedEvent.ReceivedLineItem eventLine : event.getLineItems()) {
            VendorBillLine billLine = new VendorBillLine();
            billLine.setVendorBill(savedBill);
            billLine.setLineNumber(lineNumber++);
            billLine.setProductId(eventLine.getProductId());
            billLine.setDescription(eventLine.getDescription());
            billLine.setQuantity(eventLine.getQuantity());
            billLine.setUnitPrice(eventLine.getUnitPrice());
            billLine.setLineTotal(eventLine.getQuantity().multiply(eventLine.getUnitPrice()));
            billLine.setInventoryItem(eventLine.isInventoryItem());
            billLineRepository.save(billLine);
        }

        log.info(
                "Vendor bill created | billId={} | eventId={} | poId={} | totalAmount={} | lineCount={} | status={}",
                savedBill.getVendorBillId(),
                event.getEventId(),
                event.getPurchaseOrderId(),
                totalAmount,
                event.getLineItems().size(),
                savedBill.getStatus());

        // Nothing posts at creation (AW37): the receipt's own accrual is S41's, and the bill posts once, at
        // approval (VendorBillPostingService).
        return reader.read(savedBill);
    }

    /**
     * Generates a goods-receipt bill number: {@code BILL_<VendorPrefix>_<YYYYMMDD>_<Sequence>}, for example {@code
     * BILL_A1B2C3D4_20250211_0001234}. The date is the day the bill is recorded.
     *
     * <p>The sequence is the bound tenant's own (ADR-0062 section 9; #2501): the per-tenant {@code accounting_sequence}
     * counter under scope {@code BILL-{YYYYMM}}, the month of that same date, through the {@link
     * AccountingSequenceLocker} machinery that numbers journal entries and credit memos. No database sequence is
     * involved: a shared one would hand every tenant numbers out of one series.
     *
     * <p>The counter row is read under {@code FOR UPDATE} and incremented inside the bill's own transaction, so
     * concurrent creates in a tenant take distinct numbers one after another (and the caller must request no second
     * pooled connection until that transaction ends; see {@link BillIntakePort#acceptGoodsReceipt}), and a create that
     * rolls back (a refused duplicate included) rolls the increment back with it: the number is not consumed, and the
     * next create takes it. A tenant's row for a month is created on first use by the locker; nothing provisions it.
     */
    private @NonNull String generateBillNumber(@NonNull UUID vendorId) {
        String vendorPrefix = vendorId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        // The bill's month in the tenant's accounting calendar (#2558).
        LocalDate recorded = zoneResolver.today();
        AccountingSequence sequence = sequenceLocker.lockOrProvision(
                String.format("%s%04d%02d", BILL_NUMBER_SCOPE_PREFIX, recorded.getYear(), recorded.getMonthValue()));
        long assigned = sequence.getNextValue();
        sequence.setNextValue(assigned + 1);
        return String.format(
                "BILL_%s_%s_%07d", vendorPrefix, recorded.format(DateTimeFormatter.BASIC_ISO_DATE), assigned);
    }

    // ---- machine documents (EDI) ---------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public @NonNull MachineDocumentOutcome acceptMachineDocument(
            @NonNull SupplierInvoiceReceivedV1 fact, @NonNull String eventId) {
        // S24: the vendor is the fact's pos-supplier vendorId, never its vendorProfileId.
        UUID vendorId = fact.vendorId();
        if (vendorId == null) {
            // S25: no vendor named. Not held any more: a draft a person completes by picking the vendor.
            return draftFromVendorlessFact(fact, eventId);
        }
        ExtSupplierVendor vendor = vendorCopy.findById(vendorId).orElse(null);
        if (vendor == null) {
            return new MachineDocumentOutcome.VendorNotInCopy(vendorId);
        }
        String billNumber = fact.vendorInvoiceNumber();
        LocalDateTime billDate = fact.invoiceDate().atStartOfDay();

        // The duplicate rule (#2501): a live bill of this vendor with this number, normalised, on
        // this date. A voided or rejected bill is not one, so its re-issue becomes a new bill below.
        Optional<VendorBill> existing = duplicateGuard.findOriginal(vendorId, billNumber, billDate, null);
        if (existing.isPresent()) {
            boolean flagged = flagReissue(existing.get(), fact, billNumber, eventId);
            duplicateGuard.record(
                    VendorBillDuplicateGuard.Channel.EDI,
                    flagged ? VendorBillDuplicateGuard.Outcome.FLAGGED : VendorBillDuplicateGuard.Outcome.IGNORED,
                    vendorId,
                    billNumber,
                    billDate,
                    existing.get().getVendorBillId());
            record(
                    eventId,
                    existing.get().getVendorBillId(),
                    fact,
                    flagged ? FactPostingOutcome.nothingToPost() : new FactPostingOutcome.AlreadyPosted(null, null));
            return new MachineDocumentOutcome.Bill(existing.get().getVendorBillId());
        }

        VendorBill bill = new VendorBill();
        bill.setVendorId(vendorId);
        bill.setVendorName(vendor.getDisplayName());
        // The vendor's own number, not one we mint. It is what an AP clerk quotes back to the
        // vendor, and a number of our own would be meaningless in that conversation.
        bill.setBillNumber(billNumber);
        bill.setBillDate(billDate);
        bill.setTotalAmount(signedTotal(fact));
        if (fact.totalGrossAmount() != null) {
            // The net and tax as stated (AW39), signed like the total (AW47, ruling #2509 comment 6059252089): a
            // missing tax is 0; no net stated, net = gross - tax, so a derived net never disagrees with the gross;
            // neither, net = gross and tax 0. A net and a gross stated without a tax are checked as net + 0.
            BigDecimal gross = bill.getTotalAmount();
            BigDecimal net = fact.totalNetAmount() == null ? null : signed(fact, fact.totalNetAmount());
            BigDecimal tax = fact.totalTaxAmount() == null ? null : signed(fact, fact.totalTaxAmount());
            tax = tax == null ? BigDecimal.ZERO : tax;
            if (net == null) {
                net = gross.subtract(tax);
            }
            bill.setNetAmount(net);
            bill.setTaxAmount(tax);
            bill.setStatedLineCount(Math.max(1, fact.lines().size()));
        }
        // The figure is only a sum of money with its currency, so the bill keeps the one the vendor
        // stated (ADR-0067 DF-1).
        bill.setCurrency(fact.currency());
        if (ledgerCurrency.isForeign(fact.currency())) {
            // Never booked at par (ADR-0067 PC-9, PC-13): a bill in another currency is held where an
            // operator sees why, out of matching, approval, payment and ledger-currency totals.
            bill.setStatus(VendorBillStatus.CURRENCY_HOLD);
            bill.setRejectionReason(
                    (vendor.isActive() ? "" : inactive(vendor) + ". ") + currencyHoldReason(fact.currency()));
        } else {
            // An invoice whose amount could not be read is not a nil invoice. The codec deliberately
            // records an unreadable figure as absent rather than zero so the two stay
            // distinguishable, and collapsing them here would undo that: a bill for nothing looks
            // settled, sits at the bottom of every ageing report, and is noticed when the vendor
            // chases payment.
            bill.setStatus(
                    fact.totalGrossAmount() == null
                            ? VendorBillStatus.MATCH_EXCEPTION
                            : VendorBillStatus.PENDING_RECEIPT_MATCH);
            // AW47: a document whose gross is not its net + tax, beyond the rounding tolerance, waits for a person
            // to say where the gap posts, to correct it or to void it.
            VendorBillTotals.of(bill).filter(totals -> !totals.reconciled()).ifPresent(totals -> {
                bill.setStatus(VendorBillStatus.MATCH_EXCEPTION);
                bill.setRejectionReason(totals.explanation());
            });
            // S24 (ruling 3): an inactive vendor's invoice is recorded, the debt being real, for a person to decide; if
            // its totals don't add up either, TOTALS_ADD_UP still reports FAIL and the difference is still required.
            if (!vendor.isActive()) {
                bill.setStatus(VendorBillStatus.MATCH_EXCEPTION);
                bill.setRejectionReason(
                        inactive(vendor) + (bill.getRejectionReason() == null ? "" : ". " + bill.getRejectionReason()));
            }
        }
        bill.setOriginEventId(UUID.fromString(eventId));
        bill.setOriginEventType(EDI_ORIGIN_EVENT_TYPE);
        bill.setPurchaseOrderNumber(fact.vendorOrderReference());
        bill.setCreatedBy(EDI_CREATED_BY);
        bill.setModifiedBy(EDI_CREATED_BY);

        // A new bill (no id yet) is persisted, not merged, so the id is assigned on this instance.
        // Flushed so the duplicate rule's unique index answers here, inside the handler transaction,
        // where onSupplierEvent can tell it from every other database failure.
        try {
            billRepository.saveAndFlush(bill);
        } catch (DataIntegrityViolationException e) {
            if (VendorBillDuplicateGuard.isDuplicateRuleViolation(e)) {
                throw new DuplicateRuleCollision(vendorId, billNumber, billDate, e);
            }
            throw e;
        }
        // S32d item 10 (closes G11): every bill keeps the tax its document states by type, signed like its total.
        statedTax.storeFromDocument(bill, taxByType(fact));
        record(eventId, bill.getVendorBillId(), fact, FactPostingOutcome.nothingToPost());
        log.info(
                "Created vendor bill from supplier invoice {} ({} {}) for vendor {} status={}",
                billNumber,
                fact.totalGrossAmount(),
                fact.currency(),
                vendor.getVendorNumber(),
                bill.getStatus());
        return new MachineDocumentOutcome.Bill(bill.getVendorBillId());
    }

    // ---- a vendor-less fact becomes a draft (S25) -----------------------------------------------------------

    /**
     * Writes the draft of a fact that names no vendor: channel {@code EDI}, {@code READY_FOR_REVIEW}, every value the
     * fact states {@code READ} and the vendor {@code CHECK_THIS} with resolution {@code NOT_SET_UP} until a person picks
     * one. The ingestion record is keyed on the draft's id, written in the same transaction (#2433).
     *
     * <p>The draft's number is drawn last: from there to the end of the transaction the tenant's counter row is locked.
     */
    private @NonNull MachineDocumentOutcome draftFromVendorlessFact(
            @NonNull SupplierInvoiceReceivedV1 fact, @NonNull String eventId) {
        Instant now = Instant.now(clock);
        BillIntakeItem item = new BillIntakeItem();
        item.setChannel(BillIntakeChannel.EDI);
        item.setStatus(BillIntakeStatus.READY_FOR_REVIEW);
        item.setVendorResolution(BillVendorResolution.NOT_SET_UP);
        item.setReadBack(readBackOf(fact));
        item.setUploadedBy(EDI_CREATED_BY);
        item.setUploadedAt(now);
        item.setIntakeReference(references.next());
        intakeItems.saveAndFlush(item);
        record(eventId, item.getIntakeId(), fact, FactPostingOutcome.nothingToPost());
        log.info(
                "Created bill draft {} from supplier invoice {} (no vendor named); waiting for a person",
                item.getIntakeReference(),
                fact.vendorInvoiceNumber());
        return new MachineDocumentOutcome.Draft(item.getIntakeId(), item.getIntakeReference());
    }

    /**
     * The read-back of a fact: {@code {value, state}} per field, every stated value {@code READ} (a tax label that is not
     * a tax type {@code CHECK_THIS}), the vendor {@code CHECK_THIS} carrying the supplier's alias as a hint. Text and
     * numbers only (ADR-0072). Amounts are as the document states them; the document type travels with them, because a
     * credit note is not an invoice (S28 owns the document type; confirmation refuses what it cannot yet take).
     */
    private @NonNull Map<String, Object> readBackOf(@NonNull SupplierInvoiceReceivedV1 fact) {
        Map<String, Object> readBack = new LinkedHashMap<>();
        Map<String, Object> vendor = field(null, BillFieldState.CHECK_THIS);
        vendor.put("suggestedName", fact.supplierRef());
        readBack.put("vendor", vendor);
        readBack.put("documentType", field(fact.type().name(), BillFieldState.READ));
        readBack.put("billNumber", field(fact.vendorInvoiceNumber(), BillFieldState.READ));
        readBack.put("billDate", field(fact.invoiceDate().toString(), BillFieldState.READ));
        putIfPresent(
                readBack,
                "dueDate",
                fact.dueDate() == null ? null : fact.dueDate().toString());
        putIfPresent(readBack, "terms", fact.paymentTerms());
        readBack.put("currency", field(fact.currency(), BillFieldState.READ));
        putIfPresent(readBack, "netAmount", fact.totalNetAmount());
        putIfPresent(readBack, "taxAmount", fact.totalTaxAmount());
        putIfPresent(readBack, "totalAmount", fact.totalGrossAmount());
        putIfPresent(readBack, "purchaseOrderNumber", fact.vendorOrderReference());
        if (fact.taxes() != null && !fact.taxes().isEmpty()) {
            List<Map<String, Object>> taxByType = new ArrayList<>();
            for (SupplierInvoiceTax tax : fact.taxes()) {
                String type = tax.taxType() == null ? "" : tax.taxType().trim().toUpperCase(Locale.ROOT);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("taxType", type);
                entry.put("amount", tax.amount());
                entry.put(
                        "state",
                        (TAX_TYPE_SHAPE.matcher(type).matches() ? BillFieldState.READ : BillFieldState.CHECK_THIS)
                                .name());
                taxByType.add(entry);
            }
            readBack.put("taxByType", taxByType);
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (SupplierInvoiceLine line : fact.lines()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            String partNumber = line.supplierArticleCode() != null ? line.supplierArticleCode() : line.articleEan();
            putIfPresent(entry, "partNumber", partNumber);
            putIfPresent(entry, "quantity", line.quantity());
            putIfPresent(entry, "unitCost", line.unitPrice());
            putIfPresent(entry, "lineAmount", line.lineAmount());
            lines.add(entry);
        }
        if (!lines.isEmpty()) {
            readBack.put("lines", lines);
        }
        return readBack;
    }

    private static @NonNull Map<String, Object> field(@Nullable Object value, @NonNull BillFieldState state) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("value", value);
        field.put("state", state.name());
        return field;
    }

    private static void putIfPresent(@NonNull Map<String, Object> into, @NonNull String name, @Nullable Object value) {
        if (value != null) {
            into.put(name, field(value, BillFieldState.READ));
        }
    }

    // ---- the invoice (CAP-321; S24's vendor key), moved from the supplier listener --------------------------

    /** The shape a stored tax type must have ({@code vendor_bill_tax.tax_type}'s CHECK). */
    static final Pattern TAX_TYPE_SHAPE = Pattern.compile("^[A-Z0-9_]{1,32}$");

    /** Counted, never logged by value, when a document's tax labels cannot be stored as tax types. */
    static final String UNSPLIT_LABELS_METRIC = "accounting.vendor_bill.tax_labels_unusable";

    /**
     * The fact's tax by type, signed like the bill's total; a type stated twice is added up. Null when none.
     *
     * <p>The document's label is normalised (trimmed, upper-cased in the root locale) and must then have the shape of a
     * configured tax type. The label never blocks the bill (#2664 review A1): when any label does not fit, no tax by
     * type is kept at all, so the bill reads as unsplit ({@code TAX_SPLIT_MISSING}) and nothing is recovered; only the
     * number of labels is logged and counted, never a label's value.
     */
    private @Nullable Map<String, BigDecimal> taxByType(SupplierInvoiceReceivedV1 fact) {
        if (fact.taxes() == null || fact.taxes().isEmpty()) {
            return null;
        }
        Map<String, BigDecimal> byType = new LinkedHashMap<>();
        int unusable = 0;
        for (SupplierInvoiceTax tax : fact.taxes()) {
            String type = tax.taxType() == null ? "" : tax.taxType().trim().toUpperCase(Locale.ROOT);
            if (!TAX_TYPE_SHAPE.matcher(type).matches()) {
                unusable++;
                continue;
            }
            byType.merge(type, signed(fact, tax.amount()), BigDecimal::add);
        }
        if (unusable > 0) {
            log.warn(
                    "Supplier invoice {} states {} of {} tax label(s) that are not a tax type; the bill keeps no tax"
                            + " by type and reads as unsplit (TAX_SPLIT_MISSING)",
                    fact.vendorInvoiceNumber(),
                    unusable,
                    fact.taxes().size());
            if (meterRegistry != null) {
                Counter.builder(UNSPLIT_LABELS_METRIC)
                        .description("Supplier invoices whose tax labels could not be kept as tax types (S32d)")
                        .register(meterRegistry)
                        .increment();
            }
            return null;
        }
        return byType;
    }

    private static String inactive(ExtSupplierVendor vendor) {
        return "Vendor " + vendor.getVendorNumber() + " is inactive";
    }

    private void record(String eventId, UUID domainKeyId, SupplierInvoiceReceivedV1 fact, FactPostingOutcome outcome) {
        // Argument evaluation stays outside the wrap: a payload fault there is malformed, not a
        // recorder failure.
        LocalDateTime businessTime = fact.invoiceDate().atStartOfDay();
        try {
            ingestionRecorder.record(
                    EDI_SOURCE_SYSTEM,
                    SupplierInvoiceReceivedV1.EVENT_TYPE,
                    eventId,
                    domainKeyId,
                    businessTime,
                    fact,
                    outcome);
        } catch (RuntimeException e) {
            if (RetryableConsumerFailures.isRetryable(e)) {
                throw e;
            }
            throw new IngestionRecordFailure(e);
        }
    }

    /**
     * A second invoice under a number we already hold. Not overwritten: the first version is what
     * somebody may already have approved or paid against, and replacing it would erase the
     * disagreement rather than raise it. A different amount or a different currency is flagged for
     * review; a bill held for its currency stays held, so a re-issue never releases it into a queue
     * where it could be approved at par (#2309).
     *
     * <p>An approved bill is locked (#2509, §4.3): it keeps its status and approval fields, and the
     * re-issue is recorded as an exception item linked to it, with both amounts, for a person to
     * settle by credit note or with the vendor. Bills not yet approved keep the flagging above.
     *
     * @return whether the bill was flagged; {@code false} for a re-issue identical to the bill held
     */
    private boolean flagReissue(VendorBill found, SupplierInvoiceReceivedV1 fact, String billNumber, String eventId) {
        // The bill was found without a lock: lock it and see it as it is now. A decision that voided or rejected it
        // meanwhile means it is no longer the original, so the event is retried and becomes a bill of its own.
        VendorBill bill = locks.lock(found);
        if (bill.getStatus() == VendorBillStatus.VOIDED || bill.getStatus() == VendorBillStatus.REJECTED) {
            throw new ConcurrencyFailureException("Vendor bill " + bill.getBillNumber() + " became " + bill.getStatus()
                    + " while its re-issue was being read; retried");
        }
        BigDecimal incoming = signedTotal(fact);
        boolean amountChanged = bill.getTotalAmount() != null && incoming.compareTo(bill.getTotalAmount()) != 0;
        boolean currencyChanged =
                !effectiveCurrency(bill.getCurrency()).equalsIgnoreCase(effectiveCurrency(fact.currency()));
        if (!amountChanged && !currencyChanged) {
            log.debug("Vendor invoice {} already held; nothing to do", billNumber);
            return false;
        }
        if (bill.getStatus() == VendorBillStatus.APPROVED || bill.getStatus() == VendorBillStatus.PAID) {
            UUID sourceEventId = UUID.fromString(eventId);
            if (!reissueRepository.existsBySourceEventId(sourceEventId)) {
                VendorBillReissue item = new VendorBillReissue();
                item.setVendorBillId(bill.getVendorBillId());
                item.setIncomingBillNumber(billNumber);
                item.setIncomingBillDate(fact.invoiceDate());
                item.setIncomingAmount(incoming);
                item.setIncomingCurrencyCode(effectiveCurrency(fact.currency()));
                item.setHeldAmount(bill.getTotalAmount() == null ? BigDecimal.ZERO : bill.getTotalAmount());
                item.setHeldCurrencyCode(effectiveCurrency(bill.getCurrency()));
                item.setSourceEventId(sourceEventId);
                reissueRepository.save(item);
            }
            log.info(
                    "Vendor invoice {} re-issued at {} {} against an approved bill of {} {}; recorded as an exception"
                            + " item, the bill is unchanged",
                    billNumber,
                    incoming,
                    fact.currency(),
                    bill.getTotalAmount(),
                    effectiveCurrency(bill.getCurrency()));
            return true;
        }
        String change = "Re-issued under the same number at " + incoming + " " + fact.currency() + " against a bill of "
                + bill.getTotalAmount() + " " + effectiveCurrency(bill.getCurrency());
        if (bill.getStatus() == VendorBillStatus.CURRENCY_HOLD) {
            bill.setRejectionReason(currencyHoldReason(effectiveCurrency(bill.getCurrency())) + ". " + change);
        } else {
            // Checked again from the start (#2509 review, L5): a bill that was awaiting approval keeps no submission,
            // proposal or difference, as after a CORRECT.
            bill.setStatus(VendorBillStatus.MATCH_EXCEPTION);
            bill.setRejectionReason(change);
            bill.clearSubmission();
        }
        billRepository.save(bill);
        // INFO: the one WARN for a flag is the duplicate guard's, which names the original (#2501).
        log.info(
                "Vendor invoice {} re-issued at {} {} against a bill of {} {}; flagged for review",
                billNumber,
                incoming,
                fact.currency(),
                bill.getTotalAmount(),
                effectiveCurrency(bill.getCurrency()));
        return true;
    }

    /** An absent currency is the ledger currency (ADR-0067 E-3). */
    private String effectiveCurrency(String currency) {
        return currency == null || currency.isBlank() ? ledgerCurrency.code() : currency.trim();
    }

    private String currencyHoldReason(String currency) {
        return "Currency " + currency + " is not the ledger currency " + ledgerCurrency.code()
                + "; held, never booked at par (ADR-0067 PC-9)";
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

    /** A stated amount signed by the document type, as {@link #signedTotal}; absent is zero. */
    private static BigDecimal signed(SupplierInvoiceReceivedV1 fact, BigDecimal amount) {
        BigDecimal magnitude = amount == null ? BigDecimal.ZERO : amount.abs();
        return fact.isPayable() ? magnitude : magnitude.negate();
    }
}
