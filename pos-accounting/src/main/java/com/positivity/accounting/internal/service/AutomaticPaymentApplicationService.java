package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.PaymentApplicationResponse;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.entity.ReceivablePayment.ReceivablePaymentStatus;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.ApplicationSource;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.exception.AccountingTimeZoneUnsetException;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationReversalRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import com.positivity.domainevents.payment.PaymentSettledV1;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies a settled payment to the invoice it was taken against (CAP:550 S2, #2503; spec §4.4 item 4,
 * §9.4, AW14). Runs inside the caller's transaction — the settlement listener's handler transaction,
 * or a reprocess of the fact's held row — so the application, the outcome row and the processed mark
 * commit together.
 *
 * <p>The first rule that matches decides. Before any rule that can change over time (review #2550), the
 * payment's history decides: an automatic application of it — by this path or the {@code
 * INVOICE_PAYMENT} processor — that a person undid means nothing is applied again (BR-8), and an
 * application already under this settlement's request id is the recorded result; both write no row.
 *
 * <ol type="a">
 *   <li>the method is not {@code CASH} or {@code CARD}: not applied, {@code SKIPPED / NOT_POSTABLE};
 *   <li>the invoice is not in {@code ext_invoice}: {@code SUSPENDED / INVOICE_NOT_FOUND}, retried by
 *       the retry job up to the module's attempt cap, then reprocessed by a person;
 *   <li>the invoice is not {@code FINALIZED} or {@code POSTED}: {@code FAILED / INVOICE_NOT_ELIGIBLE},
 *       retried up to the attempt cap;
 *   <li>the invoice's party, as a UUID, is missing or is not the payment's customer (BR-2, §9.5a):
 *       {@code SKIPPED / NOT_POSTABLE};
 *   <li>the tenant has no accounting-calendar zone (#2558): {@code SUSPENDED / ACCOUNTING_TIME_ZONE_UNSET},
 *       reprocessed by a person once the zone is set; never dated in a guessed zone;
 *   <li>the settlement date, in the tenant's accounting-calendar zone, is in a closed or hard-locked period (BR-5):
 *       {@code SUSPENDED / PERIOD_CLOSED}, reprocessed by a person after reopening (a hard-locked date
 *       cannot be reopened: its detail says to handle the payment by hand);
 *   <li>the payment has nothing unapplied (another path applied or credited it): nothing, no row;
 *   <li>the invoice has no open balance: {@code SKIPPED / NOT_POSTABLE}, left for a person (case g,
 *       confirmed by the Accounting Domain 2026-10-06: most often a duplicate charge, where a refund
 *       may be right);
 *   <li>otherwise the payment's whole unapplied amount is applied to that invoice only (BR-1), dated
 *       {@code settledAt}, keyed {@code PAYMENT_SETTLED:<paymentIntentId>} (BR-4), capped at the
 *       balance with any excess kept as a customer credit (AD-003) — except on the CASH walk-in account
 *       (S11, #2508; §4.4 item 4): there the excess stays unapplied on the payment, which stays {@code
 *       AVAILABLE}, is raised by {@code accounting.walk_in.overpayment} and listed by the unpaid walk-in
 *       sales read for a refund. {@code PaymentApplicationServiceImpl} enforces it for every path.
 * </ol>
 *
 * <p>An application, or case f, writes no {@code accounting_event} row: the application record is the
 * evidence. A skipped fact re-published under a new event id writes no second row.
 */
@Slf4j
@Service
public class AutomaticPaymentApplicationService {

    /** Namespace of the request id of an automatic application: {@code PAYMENT_SETTLED:<paymentIntentId>}. */
    static final String REQUEST_ID_PREFIX = ApplicationSource.PAYMENT_SETTLED.name() + ":";

    /** The methods applied automatically; the CASH house-account excess rule (S11) is in case h. */
    static final Set<String> AUTO_APPLIED_METHODS = Set.of("CASH", "CARD");

    /** The held reasons a reprocess of a {@code payment.payment.settled} row routes back here. */
    public static final Set<String> REPROCESSABLE_REASONS = Set.of(
            PostingFailureReason.PERIOD_CLOSED.name(),
            PostingFailureReason.ACCOUNTING_TIME_ZONE_UNSET.name(),
            InvoicePaymentEventProcessor.INVOICE_NOT_FOUND,
            InvoicePaymentEventProcessor.INVOICE_NOT_ELIGIBLE);

    static final String COUNTER_NAME = "accounting.payment.settled.auto_apply";

    /** What happened to one settled payment, with the status and reason its row records. */
    public enum Outcome {
        APPLIED("applied", AccountingEventStatus.PROCESSED, null),
        ALREADY_APPLIED("already_applied", AccountingEventStatus.PROCESSED, null),
        SKIPPED_METHOD("skipped_method", AccountingEventStatus.SKIPPED, PostingFailureReason.NOT_POSTABLE.name()),
        SKIPPED_PARTY("skipped_party", AccountingEventStatus.SKIPPED, PostingFailureReason.NOT_POSTABLE.name()),
        SKIPPED_PAID("skipped_paid", AccountingEventStatus.SKIPPED, PostingFailureReason.NOT_POSTABLE.name()),
        SUSPENDED_INVOICE(
                "suspended_invoice", AccountingEventStatus.SUSPENDED, InvoicePaymentEventProcessor.INVOICE_NOT_FOUND),
        SUSPENDED_PERIOD(
                "suspended_period", AccountingEventStatus.SUSPENDED, PostingFailureReason.PERIOD_CLOSED.name()),
        SUSPENDED_TIME_ZONE(
                "suspended_time_zone",
                AccountingEventStatus.SUSPENDED,
                PostingFailureReason.ACCOUNTING_TIME_ZONE_UNSET.name()),
        FAILED_INELIGIBLE(
                "failed_ineligible", AccountingEventStatus.FAILED, InvoicePaymentEventProcessor.INVOICE_NOT_ELIGIBLE);

        private final String tag;
        private final AccountingEventStatus status;
        private final @Nullable String reason;

        Outcome(String tag, AccountingEventStatus status, @Nullable String reason) {
            this.tag = tag;
            this.status = status;
            this.reason = reason;
        }

        /** The {@code outcome} tag of the {@code accounting.payment.settled.auto_apply} counter. */
        public String tag() {
            return tag;
        }

        /** The status the fact's row takes: {@code PROCESSED} for an application or case f. */
        public AccountingEventStatus status() {
            return status;
        }

        /** The {@code failureReasonCode} the fact's row records; null when nothing is held. */
        public @Nullable String reason() {
            return reason;
        }

        /** Whether the payment needs nothing more: applied now, or already applied or credited. */
        public boolean isSettled() {
            return status == AccountingEventStatus.PROCESSED;
        }
    }

    /** The decision and, for a non-application, the detail its row records. */
    public record Result(@NonNull Outcome outcome, @NonNull String detail) {}

    private final PaymentApplicationService paymentApplicationService;
    private final ReceivablePaymentRepository receivablePaymentRepository;
    private final PaymentApplicationRepository paymentApplicationRepository;
    private final PaymentApplicationReversalRepository reversalRepository;
    private final InvoiceBalanceCalculator invoiceBalanceCalculator;
    private final AccountingPeriodGate periodGate;
    private final KafkaFactIngestionRecorder ingestionRecorder;
    private final LedgerCurrency ledgerCurrency;
    private final ObjectMapper objectMapper;
    private final AccountingCalendarZoneResolver zoneResolver;
    private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);

    public AutomaticPaymentApplicationService(
            PaymentApplicationService paymentApplicationService,
            ReceivablePaymentRepository receivablePaymentRepository,
            PaymentApplicationRepository paymentApplicationRepository,
            PaymentApplicationReversalRepository reversalRepository,
            InvoiceBalanceCalculator invoiceBalanceCalculator,
            AccountingPeriodGate periodGate,
            KafkaFactIngestionRecorder ingestionRecorder,
            LedgerCurrency ledgerCurrency,
            ObjectMapper objectMapper,
            AccountingCalendarZoneResolver zoneResolver,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.paymentApplicationService = paymentApplicationService;
        this.receivablePaymentRepository = receivablePaymentRepository;
        this.paymentApplicationRepository = paymentApplicationRepository;
        this.reversalRepository = reversalRepository;
        this.invoiceBalanceCalculator = invoiceBalanceCalculator;
        this.periodGate = periodGate;
        this.ingestionRecorder = ingestionRecorder;
        this.ledgerCurrency = ledgerCurrency;
        this.objectMapper = objectMapper;
        this.zoneResolver = zoneResolver;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            for (Outcome outcome : Outcome.values()) {
                counters.put(
                        outcome,
                        Counter.builder(COUNTER_NAME)
                                .description("Settled payments by what automatic application did with them (#2503)")
                                .tag("outcome", outcome.tag())
                                .register(registry));
            }
        }
    }

    /**
     * Decide and, when the rules allow, apply a just-recorded settled payment; a non-application
     * writes the fact's {@code accounting_event} row (source system {@code pos-invoice}, domain key
     * {@code paymentIntentId}).
     *
     * @param payment         the receivable payment the fact recorded or reused
     * @param fact            the consumed fact
     * @param envelopeEventId the consumed envelope's event id, kept on the row as its ingestion id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public @NonNull Result applySettled(
            @NonNull ReceivablePayment payment, @NonNull PaymentSettledV1 fact, @NonNull String envelopeEventId) {
        Result result = decideAndApply(payment, fact);
        Outcome outcome = result.outcome();
        if (outcome.status() == AccountingEventStatus.SKIPPED) {
            ingestionRecorder.recordSkippedOnce(
                    SettlementEventsListener.PAYMENT_SETTLED_SOURCE_SYSTEM,
                    PaymentSettledV1.EVENT_TYPE,
                    envelopeEventId,
                    fact.paymentIntentId(),
                    transactionDate(fact),
                    fact,
                    PostingFailureReason.NOT_POSTABLE,
                    result.detail());
        } else if (!outcome.isSettled()) {
            ingestionRecorder.recordSuspended(
                    SettlementEventsListener.PAYMENT_SETTLED_SOURCE_SYSTEM,
                    PaymentSettledV1.EVENT_TYPE,
                    envelopeEventId,
                    fact.paymentIntentId(),
                    transactionDate(fact),
                    fact,
                    outcome.status(),
                    Objects.requireNonNull(outcome.reason()),
                    result.detail());
        }
        return result;
    }

    /**
     * Re-run the decision for a held fact from its stored payload (item 6): never records the payment
     * again and writes no row; the caller updates the held row from the result.
     *
     * @param storedFact the {@code payload} of the fact's {@code accounting_event} row
     * @throws IllegalStateException when the payload is unreadable or the payment was never recorded
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public @NonNull Result reapply(@NonNull Map<String, Object> storedFact) {
        PaymentSettledV1 fact;
        try {
            fact = objectMapper.convertValue(storedFact, PaymentSettledV1.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Stored payment.payment.settled payload is unreadable: " + e.getMessage(), e);
        }
        if (fact == null || fact.paymentIntentId() == null || fact.settledAt() == null) {
            throw new IllegalStateException(
                    "Stored payment.payment.settled payload has no paymentIntentId or settledAt");
        }
        ReceivablePayment payment = receivablePaymentRepository
                .findById(fact.paymentIntentId())
                .orElseThrow(() -> new IllegalStateException(
                        "Settled payment " + fact.paymentIntentId() + " was never recorded; nothing to apply"));
        return decideAndApply(payment, fact);
    }

    private Result decideAndApply(ReceivablePayment payment, PaymentSettledV1 fact) {
        Result result = decide(payment, fact);
        counter(result.outcome());
        if (result.outcome() == Outcome.APPLIED || result.outcome() == Outcome.ALREADY_APPLIED) {
            log.info(
                    "Settled payment automatic application | paymentIntentId={} | invoice={} | outcome={} | {}",
                    fact.paymentIntentId(),
                    invoiceLabel(fact),
                    result.outcome().tag(),
                    result.detail());
        } else {
            log.warn(
                    "Settled payment not applied automatically | paymentIntentId={} | outcome={} | {}",
                    fact.paymentIntentId(),
                    result.outcome().tag(),
                    result.detail());
        }
        return result;
    }

    private Result decide(ReceivablePayment payment, PaymentSettledV1 fact) {
        // 0. Already decided for this payment, before any rule that can change over time (review #2550): an
        // undone automatic application by either path is never repeated (BR-8), and an application under
        // this settlement's request id is the recorded result of a redelivery, re-publish or reprocess.
        String requestId = REQUEST_ID_PREFIX + fact.paymentIntentId();
        if (reversalRepository.existsReversedAutomaticApplication(payment.getPaymentId())) {
            return new Result(
                    Outcome.ALREADY_APPLIED, "an automatic application of this payment was undone; not applied again");
        }
        if (paymentApplicationRepository.existsByApplicationRequestId(requestId)) {
            return new Result(Outcome.ALREADY_APPLIED, "applied earlier under " + requestId);
        }

        // a. Only CASH and CARD are applied automatically.
        String method =
                fact.methodType() == null ? null : fact.methodType().trim().toUpperCase(Locale.ROOT);
        if (method == null || !AUTO_APPLIED_METHODS.contains(method)) {
            return new Result(Outcome.SKIPPED_METHOD, "method " + fact.methodType() + " is not applied automatically");
        }

        // b. The invoice must be replicated; replica lag makes this retryable.
        UUID invoiceId = fact.invoiceId();
        Optional<ExtInvoice> found =
                invoiceId == null ? Optional.empty() : invoiceBalanceCalculator.findInvoice(invoiceId);
        if (found.isEmpty()) {
            return new Result(
                    Outcome.SUSPENDED_INVOICE,
                    "invoice " + invoiceLabel(fact) + " is not in the invoice replica yet; retried once it arrives");
        }
        ExtInvoice invoice = found.get();
        String invoiceNumber = invoiceNumber(invoice, fact);

        // c. The invoice must be in AR.
        if (!invoiceBalanceCalculator.isArEligible(invoice)) {
            return new Result(
                    Outcome.FAILED_INELIGIBLE,
                    "invoice " + invoiceNumber + " cannot take a payment (status: " + invoice.getStatus() + ")");
        }

        // d. Strict party equality by UUID (BR-2, §9.5a).
        if (!payment.getCustomerId().equals(partyUuid(invoice.getPartyId()))) {
            return new Result(Outcome.SKIPPED_PARTY, "customer differs from invoice " + invoiceNumber);
        }

        // e. Dated in the tenant's accounting calendar (#2558), never in a guessed zone; then never into a closed or
        // hard-locked period, and no override: nobody is there to give one.
        LocalDate settledOn;
        try {
            settledOn = zoneResolver.postingDate(fact.settledAt());
        } catch (AccountingTimeZoneUnsetException e) {
            return new Result(
                    Outcome.SUSPENDED_TIME_ZONE,
                    "the tenant's accounting time zone is not set, so the settlement has no posting date; reprocess"
                            + " after setting it");
        }
        if (periodGate.isPostingBlocked(settledOn)) {
            return new Result(
                    Outcome.SUSPENDED_PERIOD,
                    periodGate.isHardLocked(settledOn)
                            ? "settlement date " + settledOn + " is before the hard-lock date, which cannot be"
                                    + " reopened; match or credit the payment by hand"
                            : "settlement date " + settledOn + " is in a closed period; reprocess after reopening"
                                    + " it");
        }

        // f. Another path applied or credited it first: nothing to do, nothing to record.
        BigDecimal unapplied = payment.getUnappliedAmount();
        if (payment.getStatus() != ReceivablePaymentStatus.AVAILABLE || unapplied == null || unapplied.signum() <= 0) {
            return new Result(Outcome.ALREADY_APPLIED, "payment has nothing unapplied");
        }

        // g. A payment against a paid invoice is left for a person (the open point on #2503).
        BigDecimal balanceDue = invoiceBalanceCalculator.balanceDue(invoice);
        if (!InvoiceBalanceCalculator.isOpenReceivable(invoice, balanceDue, ledgerCurrency.code())) {
            return new Result(
                    Outcome.SKIPPED_PAID, "invoice " + invoiceNumber + " has no open balance; left for a person");
        }

        // h. Apply the whole unapplied amount to that invoice only (BR-1), dated settledAt (BR-5).
        PaymentApplicationResponse response = paymentApplicationService.applyAutomatically(
                payment.getPaymentId(),
                invoiceId,
                unapplied,
                requestId,
                fact.settledAt(),
                ApplicationSource.PAYMENT_SETTLED);
        return new Result(
                Outcome.APPLIED,
                "applied " + response.getAppliedAmount() + " to invoice " + invoiceNumber + excessDetail(response));
    }

    /** What became of an excess: a customer credit, or, on the CASH walk-in account, left unapplied (#2508). */
    private static String excessDetail(PaymentApplicationResponse response) {
        if (response.getCustomerCredit() != null) {
            return "; " + response.getCustomerCredit().getAmount() + " kept as customer credit";
        }
        BigDecimal remaining = response.getRemainingAmount();
        if (remaining != null && remaining.signum() > 0) {
            return "; " + remaining + " left unapplied (walk-in excess: refunded, never credited)";
        }
        return "";
    }

    private void counter(Outcome outcome) {
        Counter counter = counters.get(outcome);
        if (counter != null) {
            counter.increment();
        }
    }

    private LocalDateTime transactionDate(PaymentSettledV1 fact) {
        return zoneResolver.heldRecordDateTime(fact.settledAt());
    }

    private static @Nullable UUID partyUuid(@Nullable String partyId) {
        if (partyId == null || partyId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(partyId.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The invoice's number for a message: the replica's, else the fact's, else its id. */
    private static String invoiceNumber(ExtInvoice invoice, PaymentSettledV1 fact) {
        if (invoice.getInvoiceNumber() != null && !invoice.getInvoiceNumber().isBlank()) {
            return invoice.getInvoiceNumber();
        }
        return invoiceLabel(fact);
    }

    private static String invoiceLabel(PaymentSettledV1 fact) {
        if (fact.invoiceNumber() != null && !fact.invoiceNumber().isBlank()) {
            return fact.invoiceNumber();
        }
        return String.valueOf(fact.invoiceId());
    }
}
