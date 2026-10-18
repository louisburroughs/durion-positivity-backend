package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.billintake.service.BillIntakePort;
import com.positivity.accounting.internal.config.AccountingEventTypeRegistry;
import com.positivity.accounting.internal.entity.ExtSupplierVendor;
import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.entity.SupplierInvoiceHold;
import com.positivity.accounting.internal.repository.ExtSupplierVendorRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.internal.repository.SupplierInvoiceHoldRepository;
import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.supplier.SupplierInvoiceReceivedV1;
import com.positivity.domainevents.supplier.SupplierVendorUpdatedV1;
import com.positivity.kafka.common.KafkaRails;
import com.positivity.tenancy.kafka.RetryableConsumerFailures;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.Serial;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The module's one consumer of {@code supplier.events.v1}: AP vendor bills from vendor invoices fetched over EDIWheel
 * B3.3 (CAP-321 #1227), and accounting's copy of the pos-supplier vendor master (CAP:550 S24, #2517).
 *
 * <h2>One consumer, every supplier fact marked</h2>
 *
 * The vendor branch is added here rather than in a listener of its own: {@code processed_events} is keyed by event id,
 * so a second consumer group on the topic would suppress this one. Every fact this consumer reads, handled or not, is
 * marked with {@code owner = "supplier"} (and the tenant), which is what {@link SupplierManifestListener} compares with
 * pos-supplier's manifest.
 *
 * <h2>The vendor copy (S24)</h2>
 *
 * A {@code supplier.vendor.updated} fact upserts {@code ext_supplier_vendor} under {@link ReplicaVersionGuard}: it
 * applies when its {@code aggregateVersion} is at least the stored one, so a replay repairs and a late older fact
 * changes nothing. Only {@code schemaVersion} 2 or later is applied (Security ruling #2617, ADR-0072): the version is
 * read from the envelope before the payload is mapped, and an older fact is marked, counted as {@code
 * accounting.supplier_vendor.skipped{eventType, schemaVersion}} and skipped, its payload never logged. Seeding comes
 * from pos-supplier's {@code POST /v1/supplier/vendors/facts/replay}. No log line, metric tag or exception message of
 * the vendor branch names a tax registration, {@code last4} included.
 *
 * <h2>The vendor key (S24)</h2>
 *
 * An invoice names its vendor by the fact's {@code vendorId}, never by {@code vendorProfileId}. A fact without one
 * becomes a bill draft ({@code BillIntakeItem}, channel {@code EDI}, CAP:550 S25 #2518) that a person completes by
 * picking the vendor; it used to be held as {@code VENDOR_ID_MISSING}, a reason now retired (the open holds are
 * converted once, by {@code VendorlessHoldConverter}). A vendor not yet copied is held ({@code VENDOR_NOT_IN_COPY}) and
 * released, through the same bill creation, once the vendor is copied. A hold is written with the processed mark, so an
 * invoice fact is never dropped. An {@code INACTIVE} vendor's invoice becomes a {@code MATCH_EXCEPTION} bill naming the
 * vendor as inactive (ruling 3). The bill, the draft, the S0 duplicate rule and the ingestion record are {@link
 * BillIntakePort#acceptMachineDocument}'s; this listener keeps the parse, the event-id dedupe, the holds and the
 * transaction shape.
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
 * <p><strong>2. No journal entry is created on ingest.</strong> The bill posts once, at approval (AW37;
 * {@code VendorBillPostingService}, #2509), and an unapproved vendor
 * invoice is a claim rather than a liability anyone has agreed to. Posting on arrival would put
 * money in the ledger on the vendor's say-so alone, and a vendor that invoices in error would move
 * our accounts before anybody looked at it.
 *
 * <p><strong>3. The pos-supplier vendor id is the accounting vendor id</strong> (S24 replaces the vendor profile id
 * used before): one vendor appears once in aged payables and in the duplicate rule, whichever channel its bills
 * arrive by.
 *
 * <h2>One bill per invoice, however many times we are told</h2>
 *
 * Guarded twice, because the two failures are different. The event-id guard catches a redelivery of
 * the same message. The vendor-invoice-identity guard catches the same document being fetched again
 * — which happens by design, since fetch windows overlap so a failed fetch can be repeated safely.
 * Only the second would survive a rebuilt supplier database, and it is the one that stops a debt
 * being recorded twice.
 *
 * <p>The identity is the platform's one duplicate rule (#2501; ADR-0070 Decision 4), asked of {@link
 * VendorBillDuplicateGuard}: the vendor, the normalised invoice number and the invoice date, among
 * bills that are not {@code VOIDED} or {@code REJECTED}. So a number reused on another date is a new
 * bill, and so is a re-issue after the original was voided or rejected; the old bill is left as it
 * is. A duplicate is never thrown and never dropped: it is flagged on the original when it differs,
 * or recorded as ignored when it is identical, because then the debt is already held.
 *
 * <p>The database enforces the same rule with a partial unique index, and the insert is flushed
 * inside the handler transaction so that a concurrent writer of the same key surfaces there. That one
 * violation is not retried by the container: the handler runs once more in a new transaction, finds
 * the original the other writer committed, and takes the duplicate path. A second collision, like
 * every other database failure, propagates unmarked.
 *
 * <h2>Transaction shape (#2146)</h2>
 *
 * The bill and its processed mark commit together in a {@code REQUIRES_NEW} transaction of their
 * own, so an unreadable invoice that fails inside a repository call rolls back only that work and
 * is recorded in a separate transaction; every database failure still propagates for retry,
 * unrecorded, as above. So does any failure writing the fact's {@code accounting_event} row (#2433),
 * database or not ({@link BillIntakePort.IngestionRecordFailure}): marking it processed would lose the bill along
 * with the record, since both roll back together.
 *
 * <h2>Ingestion record (#2433)</h2>
 *
 * Every consumed {@code supplier.invoice.received} fact writes one {@code accounting_event} row
 * through {@link KafkaFactIngestionRecorder} in the same transaction, keyed on the vendor bill id
 * (source system {@value #SOURCE_SYSTEM}). Nothing posts on ingest (judgment 2), so a new bill, or a
 * re-issue flagged for review, is {@code PROCESSED / NEW} with no journal entry; a re-issue identical
 * to the bill already held is {@code PROCESSED / DUPLICATE_IGNORED}.
 */
@Slf4j
@Component
@KafkaRails
public class SupplierEventsListener {

    /** Producing domain, per the repo-wide {@code processed_events} convention. */
    static final String OWNER = "supplier";

    /** Producing module, stamped as {@code sourceSystem} on this listener's ingestion records. */
    public static final String SOURCE_SYSTEM = BillIntakePort.EDI_SOURCE_SYSTEM;

    /**
     * Event type codes this listener records an {@code accounting_event} row for, one per consumed
     * fact (#2433).
     */
    public static final List<String> RECORDED_EVENT_TYPES =
            AccountingEventTypeRegistry.kafkaCodes(AccountingEventTypeRegistry.DOMAIN_SUPPLIER);

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtSupplierVendorRepository vendorCopy;
    private final SupplierInvoiceHoldRepository holds;
    private final VendorBillDuplicateGuard duplicateGuard;
    private final BillIntakePort billIntake;
    private final @Nullable MeterRegistry meterRegistry;

    /** The handler plus its processed mark, or a failure's mark alone, per transaction; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public SupplierEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtSupplierVendorRepository vendorCopy,
            SupplierInvoiceHoldRepository holds,
            VendorBillDuplicateGuard duplicateGuard,
            BillIntakePort billIntake,
            ObjectProvider<MeterRegistry> meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.vendorCopy = vendorCopy;
        this.holds = holds;
        this.duplicateGuard = duplicateGuard;
        this.billIntake = billIntake;
        this.meterRegistry = meterRegistry.getIfAvailable();
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @KafkaListener(
            topics = "${pos.accounting.kafka.supplier-events-topic:supplier.events.v1}",
            groupId = "pos-accounting-supplier-events")
    public void onSupplierEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            // The parser's message may quote the record: its class only (ADR-0072).
            log.warn("Skipping unparsable supplier event ({})", e.getClass().getSimpleName());
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
        handle(envelope, eventType, eventId, true);
    }

    /**
     * The handler and its processed mark in one {@code REQUIRES_NEW} transaction, and what becomes of
     * each failure.
     *
     * @param mayRunOnceMore whether a collision under the duplicate rule's unique index may be
     *     answered by one more run; false on that second run, whose collision propagates
     */
    private void handle(JsonNode envelope, String eventType, String eventId, boolean mayRunOnceMore) {
        boolean vendorFact = SupplierVendorUpdatedV1.EVENT_TYPE.equals(eventType);
        UUID copied;
        try {
            copied = handlerTransaction.execute(_ -> {
                UUID vendorId = null;
                if (SupplierInvoiceReceivedV1.EVENT_TYPE.equals(eventType)) {
                    applyInvoice(envelope, eventId, true);
                } else if (vendorFact) {
                    vendorId = applyVendor(envelope, eventId);
                } else {
                    log.debug("Ignoring supplier event type={} eventId={}", eventType, eventId);
                }
                markProcessed(eventId);
                return vendorId;
            });
        } catch (VendorCopyWriteFailure e) {
            throw e.getCause();
        } catch (BillIntakePort.IngestionRecordFailure e) {
            throw e.getCause();
        } catch (BillIntakePort.DuplicateRuleCollision e) {
            if (mayRunOnceMore) {
                // A concurrent writer committed the same (vendor, number, date) between this
                // handler's check and its insert (#2501). Redelivery would find that bill too, but
                // only after the container's backoff; the transaction above has rolled back, so the
                // handler runs once more now and takes the duplicate path against the committed bill.
                duplicateGuard.record(
                        VendorBillDuplicateGuard.Channel.EDI,
                        VendorBillDuplicateGuard.Outcome.RETRIED,
                        e.vendorId(),
                        e.billNumber(),
                        e.billDate(),
                        null);
                handle(envelope, eventType, eventId, false);
                return;
            }
            throw e.getCause();
        } catch (DataAccessException e) {
            // Every database failure is rethrown, not only the transient ones. A column-length
            // violation or a concurrent vendor insert is not a malformed message, and marking it
            // processed would lose a vendor debt permanently — the supplier side has already
            // published this invoice and will not publish it again, so nothing would bring it back.
            // Better a retry, or a dead letter somebody has to look at, than a debt that vanishes.
            throw e;
        } catch (Exception e) {
            if (RetryableConsumerFailures.isRetryable(e)) {
                // Not a DataAccessException, but as retryable as one: the transaction could not be
                // opened, failed to commit or timed out, or a lost connection came wrapped.
                // Rethrown before the mark below for the same reason as above (ADR-0044 §4, #2355).
                throw e;
            }
            // Genuinely unreadable: a payload this build cannot parse will not parse on retry
            // either, and blocking the partition would stop every other vendor's invoices too.
            if (vendorFact) {
                // A vendor fact's mapping error may quote the payload (ADR-0072): its class only, never its message.
                log.warn(
                        "Skipping malformed supplier event eventId={} eventType={} ({})",
                        eventId,
                        eventType,
                        e.getClass().getSimpleName());
            } else {
                log.warn("Skipping malformed supplier invoice event eventId={}", eventId, e);
            }
            handlerTransaction.executeWithoutResult(_ -> markProcessed(eventId));
            return;
        }
        if (copied != null) {
            releaseHolds(copied);
        }
    }

    private void markProcessed(@NonNull String eventId) {
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    // ---- the vendor copy (S24) ------------------------------------------------------------------------------

    /**
     * Applies a {@code supplier.vendor.updated} fact to the copy (rule 1): {@code schemaVersion} 2 or later only, read
     * from the envelope before the payload is mapped; then the version guard; then the upsert.
     *
     * @return the vendor id when the copy was written, so its held invoices can be released; null when skipped
     */
    private @Nullable UUID applyVendor(JsonNode envelope, String eventId) {
        int schemaVersion = envelope.path("schemaVersion").intValue(0);
        if (schemaVersion < SupplierVendorUpdatedV1.SCHEMA_VERSION) {
            // Security ruling #2617: a version 1 fact may carry a full registration number. It is never mapped, never
            // applied and never logged; it is marked by the caller, so the manifest still counts it.
            countSkippedVendorFact(schemaVersion);
            log.info(
                    "Skipping supplier vendor fact below schemaVersion {} eventId={} eventType={} schemaVersion={}",
                    SupplierVendorUpdatedV1.SCHEMA_VERSION,
                    eventId,
                    SupplierVendorUpdatedV1.EVENT_TYPE,
                    schemaVersion);
            return null;
        }
        SupplierVendorUpdatedV1 fact =
                objectMapper.treeToValue(envelope.path("payload"), SupplierVendorUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtSupplierVendor copy = vendorCopy.findById(fact.vendorId()).orElse(null);
        if (copy != null && ReplicaVersionGuard.isStale(copy.getAggregateVersion(), aggregateVersion)) {
            log.debug(
                    "Ignoring stale supplier vendor fact eventId={} vendorId={} version={} held={}",
                    eventId,
                    fact.vendorId(),
                    aggregateVersion,
                    copy.getAggregateVersion());
            return null;
        }
        if (copy == null) {
            copy = new ExtSupplierVendor();
            copy.setVendorId(fact.vendorId());
        }
        copy.setVendorNumber(fact.vendorNumber());
        copy.setDisplayName(fact.displayName());
        copy.setStatus(fact.status().name());
        copy.setStatusChangedAt(fact.statusChangedAt());
        copy.setRemitToVersion(fact.remitToVersion());
        copy.setRemitToChangedAt(fact.remitToChangedAt());
        copy.setRemitToRequestedBy(fact.remitToRequestedBy());
        copy.setDefaultPaymentTerms(fact.defaultPaymentTerms());
        copy.setDefaultCurrency(fact.defaultCurrency());
        // As the fact carries them, {scheme, region, last4}; never validated, never read by a rule here (AW48).
        copy.setTaxRegistrations(fact.taxRegistrations().stream()
                .map(SupplierEventsListener::registration)
                .toList());
        copy.setCreatedBy(fact.createdBy());
        copy.setAggregateVersion(aggregateVersion);
        // updated_at is the auditing listener's (@LastModifiedDate), its single source.
        try {
            vendorCopy.saveAndFlush(copy);
        } catch (DataIntegrityViolationException e) {
            // Postgres quotes the failing row in a constraint violation's detail, registrations included: the
            // exception that propagates (and may reach a DLQ) carries only the constraint's name (ADR-0072).
            throw new VendorCopyWriteFailure(new DataIntegrityViolationException("The vendor copy refused vendor "
                    + fact.vendorNumber() + " (eventId " + eventId + "): " + constraintName(e)));
        }
        log.info(
                "Copied supplier vendor {} vendorId={} version={} status={} remitToVersion={}",
                fact.vendorNumber(),
                fact.vendorId(),
                aggregateVersion,
                fact.status(),
                fact.remitToVersion());
        return fact.vendorId();
    }

    private static Map<String, String> registration(SupplierVendorUpdatedV1.TaxRegistration registration) {
        Map<String, String> copy = new LinkedHashMap<>();
        copy.put("scheme", registration.scheme());
        copy.put("region", registration.region());
        copy.put("last4", registration.last4());
        return copy;
    }

    private static String constraintName(DataIntegrityViolationException e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && violation.getConstraintName() != null) {
                return "constraint " + violation.getConstraintName();
            }
            cause = cause.getCause();
        }
        return "a constraint violation";
    }

    /** One {@code accounting.supplier_vendor.skipped} increment, tagged with the event type and schema version only. */
    private void countSkippedVendorFact(int schemaVersion) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("accounting.supplier_vendor.skipped")
                .description("supplier.vendor.updated facts below schemaVersion 2, marked and never applied")
                .tag("eventType", SupplierVendorUpdatedV1.EVENT_TYPE)
                .tag("schemaVersion", Integer.toString(schemaVersion))
                .register(meterRegistry)
                .increment();
    }

    /**
     * Releases the vendor's {@code VENDOR_NOT_IN_COPY} holds once it is copied (rule 3), oldest first, each through the
     * same bill creation in a transaction of its own, with its ingestion record. A release that fails leaves its hold
     * {@code HELD} for {@link SupplierInvoiceHoldSweep} or the vendor's next fact to retry; the vendor fact is already
     * marked.
     */
    void releaseHolds(@NonNull UUID vendorId) {
        List<UUID> open = handlerTransaction.execute(_ -> holds
                .findByVendorIdAndReasonAndReleasedAtIsNullOrderByReceivedAtAscHoldIdAsc(
                        vendorId, SupplierInvoiceHold.Reason.VENDOR_NOT_IN_COPY)
                .stream()
                .map(SupplierInvoiceHold::getHoldId)
                .toList());
        if (open == null) {
            return;
        }
        for (UUID holdId : open) {
            try {
                release(holdId, true);
            } catch (RuntimeException e) {
                // The vendor fact is committed and marked: rethrowing would only redeliver a fact the event-id guard
                // skips. Whether the failure is transient or not, the hold stays HELD and SupplierInvoiceHoldSweep (or
                // the vendor's next fact) retries the release; the classification only says which it was.
                boolean transientFailure = RetryableConsumerFailures.isRetryable(e);
                log.warn(
                        "Releasing held supplier invoice holdId={} of vendor {} failed ({}); it stays held and is"
                                + " retried",
                        holdId,
                        vendorId,
                        transientFailure ? "transient" : "not transient",
                        e);
            }
        }
    }

    private void release(UUID holdId, boolean mayRunOnceMore) {
        try {
            handlerTransaction.executeWithoutResult(_ -> {
                // Locked, and released_at re-read under the lock: a sweep and a vendor fact releasing the same hold at
                // once serialise here, and the second finds it released.
                SupplierInvoiceHold hold = holds.lockByHoldId(holdId).orElse(null);
                if (hold == null || hold.getReleasedAt() != null) {
                    return;
                }
                JsonNode envelope = objectMapper.readTree(hold.getPayload());
                UUID billId = applyInvoice(envelope, hold.getEventId().toString(), false);
                if (billId == null) {
                    return;
                }
                hold.setReleasedAt(Instant.now(clock));
                hold.setReleasedBillId(billId);
                holds.save(hold);
                countHoldReleased();
                log.info(
                        "Released held supplier invoice {} eventId={} as bill {}",
                        hold.getSupplierInvoiceRef(),
                        hold.getEventId(),
                        billId);
            });
        } catch (BillIntakePort.IngestionRecordFailure e) {
            throw e.getCause();
        } catch (BillIntakePort.DuplicateRuleCollision e) {
            if (!mayRunOnceMore) {
                throw e.getCause();
            }
            duplicateGuard.record(
                    VendorBillDuplicateGuard.Channel.EDI,
                    VendorBillDuplicateGuard.Outcome.RETRIED,
                    e.vendorId(),
                    e.billNumber(),
                    e.billDate(),
                    null);
            release(holdId, false);
        }
    }

    private void countHoldReleased() {
        if (meterRegistry != null) {
            meterRegistry.counter("accounting.supplier_invoice.hold_released").increment();
        }
    }

    // ---- the invoice (CAP-321; S24's vendor key) -----------------------------------------------------------------

    /**
     * Turns an invoice fact into a bill, a flagged duplicate (S0), a draft (no vendor named, S25) or, when {@code
     * holdIfUnready}, a hold: the work is {@link BillIntakePort#acceptMachineDocument}'s, in this handler's transaction.
     *
     * @param holdIfUnready false on a release: a fact whose vendor is still not copied stays held, unchanged
     * @return the bill created or flagged against; null when the fact was held (or, on a release, is not ready) or
     *     became a draft
     */
    private @Nullable UUID applyInvoice(JsonNode envelope, String eventId, boolean holdIfUnready) {
        SupplierInvoiceReceivedV1 fact =
                objectMapper.treeToValue(envelope.path("payload"), SupplierInvoiceReceivedV1.class);
        return switch (billIntake.acceptMachineDocument(fact, eventId)) {
            case BillIntakePort.MachineDocumentOutcome.Bill bill -> bill.billId();
            case BillIntakePort.MachineDocumentOutcome.Draft _ -> null;
            case BillIntakePort.MachineDocumentOutcome.VendorNotInCopy notCopied -> {
                if (holdIfUnready) {
                    hold(envelope, eventId, fact, notCopied.vendorId(), SupplierInvoiceHold.Reason.VENDOR_NOT_IN_COPY);
                }
                yield null;
            }
        };
    }

    /**
     * Holds the fact (rule 3): its whole envelope, its event id and the reason, written in the handler transaction with
     * the processed mark, so the fact is never dropped. No ingestion record is written: the release writes it with the
     * bill.
     */
    private void hold(
            JsonNode envelope,
            String eventId,
            SupplierInvoiceReceivedV1 fact,
            @Nullable UUID vendorId,
            SupplierInvoiceHold.Reason reason) {
        SupplierInvoiceHold hold = new SupplierInvoiceHold();
        hold.setEventId(UUID.fromString(eventId));
        String ref = fact.vendorInvoiceNumber();
        hold.setSupplierInvoiceRef(ref.length() > HOLD_REF_LENGTH ? ref.substring(0, HOLD_REF_LENGTH) : ref);
        hold.setVendorId(vendorId);
        hold.setReason(reason);
        hold.setPayload(objectMapper.writeValueAsString(envelope));
        hold.setReceivedAt(Instant.now(clock));
        holds.save(hold);
        log.info(
                "Held supplier invoice {} eventId={} reason={} vendorId={}",
                hold.getSupplierInvoiceRef(),
                eventId,
                reason,
                vendorId);
    }

    /** {@code supplier_invoice_hold.supplier_invoice_ref} is {@code varchar(100)}. */
    private static final int HOLD_REF_LENGTH = 100;

    /**
     * A vendor-copy write refused by a constraint, its message stripped of the failing row (ADR-0072). Carried out of
     * the handler transaction past the malformed-payload catch, so {@link #handle} rethrows it unmarked.
     */
    private static final class VendorCopyWriteFailure extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        VendorCopyWriteFailure(@NonNull DataIntegrityViolationException cause) {
            super(cause.getMessage(), cause);
        }

        @Override
        public synchronized @NonNull DataIntegrityViolationException getCause() {
            return (DataIntegrityViolationException) super.getCause();
        }
    }
}
