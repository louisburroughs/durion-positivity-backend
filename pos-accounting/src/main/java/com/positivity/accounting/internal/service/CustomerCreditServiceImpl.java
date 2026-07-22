package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.CustomerCreditApplicationRequest;
import com.positivity.accounting.internal.dto.CustomerCreditRefundRequest;
import com.positivity.accounting.internal.dto.CustomerCreditReliefGLPostingEvent;
import com.positivity.accounting.internal.dto.CustomerCreditResponse;
import com.positivity.accounting.internal.dto.CustomerCreditTransactionResponse;
import com.positivity.accounting.internal.entity.CustomerCredit;
import com.positivity.accounting.internal.entity.CustomerCreditTransaction;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.enums.CustomerCreditStatus;
import com.positivity.accounting.internal.enums.CustomerCreditTransactionType;
import com.positivity.accounting.internal.repository.CustomerCreditRepository;
import com.positivity.accounting.internal.repository.CustomerCreditTransactionRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.service.AccountingPeriodService;
import com.positivity.accounting.service.CustomerCreditService;
import com.positivity.accounting.service.OutboxService;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Customer-credit draw-down and GL liability relief (story parity-C1 follow-on, issue #992).
 *
 * <p><b>Why this exists.</b> Overpayment issuance (#975) posts {@code Dr Undeposited Funds /
 * Cr Customer Credit Liability (2300)}, which only ever <em>recognizes</em> the obligation. With
 * no consumption path the 2300 control account grew monotonically and the #975 AC-7 invariant
 * (Σ open customer credits == the 2300 balance) held only while credits sat unused. This service
 * supplies the missing half:
 *
 * <ul>
 *   <li><b>Application</b> — the credit settles a later invoice:
 *       {@code Dr 2300 / Cr Accounts Receivable}. Liability down, receivable down.</li>
 *   <li><b>Refund</b> — cash goes back to the customer:
 *       {@code Dr 2300 / Cr Undeposited Funds}. Liability down, cash out.</li>
 * </ul>
 *
 * <p>Across issuance → application/refund the 2300 balance nets to zero for a fully-consumed
 * credit, and partial draw-downs leave a residual liability matching the credit's residual open
 * amount — which is exactly the AC-7 reconciliation.
 *
 * <p><b>Guarantees</b>, mirroring the issuance leg:
 *
 * <ul>
 *   <li><b>Same-transaction outbox</b> — the relief work item is enqueued with MANDATORY
 *       propagation inside this transaction, so the ledger work item exists iff the draw-down
 *       committed.</li>
 *   <li><b>Idempotent</b> — the caller's {@code requestId} is unique on
 *       {@code customer_credit_transaction}; a replay of the <em>same</em> operation returns the
 *       original draw-down and enqueues nothing, while reusing the key for a different credit,
 *       operation, invoice, or amount is a 409 rather than a silent no-op. The handler
 *       additionally guards the posting with its own idempotency key.</li>
 *   <li><b>Period-gated twice</b> — the draw-down is refused up front when the target period is
 *       not open, so a relief can never be stranded behind a closed period while the subledger
 *       already reads consumed; the posting handler still surfaces the Wave 2 period-gate
 *       exceptions for a period that closes between enqueue and posting.</li>
 *   <li><b>Concurrency-safe on both axes</b> — {@code CustomerCredit} carries a {@code @Version},
 *       so two draw-downs of the same credit cannot both pass the open-amount check; and the
 *       target invoice is loaded {@code FOR UPDATE}, so two draw-downs of <em>different</em>
 *       credits cannot both pass the balance-due check and over-credit one receivable.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CustomerCreditServiceImpl implements CustomerCreditService {

    private final Clock clock;
    private final CustomerCreditRepository customerCreditRepository;
    private final CustomerCreditTransactionRepository creditTransactionRepository;
    private final ExtInvoiceRepository extInvoiceRepository;
    private final InvoiceBalanceCalculator invoiceBalanceCalculator;
    private final AccountingPeriodService periodService;
    private final OutboxService outboxService;

    @Override
    @NonNull
    public CustomerCreditTransactionResponse applyCreditToInvoice(
            @NonNull UUID creditId, @NonNull CustomerCreditApplicationRequest request, @NonNull String currentUser) {

        BigDecimal amount = validateAmount(request.getAmount());

        CustomerCreditTransaction replay = creditTransactionRepository
                .findByRequestId(request.getRequestId())
                .orElse(null);
        if (replay != null) {
            requireReplayMatches(
                    replay, creditId, CustomerCreditTransactionType.APPLICATION, request.getInvoiceId(), amount);
            return replayResponse(replay, "application");
        }

        CustomerCredit credit = fetchCredit(creditId);
        validateOpenAmount(credit, amount);

        ExtInvoice invoice = fetchInvoiceForUpdate(request.getInvoiceId());
        if (!invoiceBalanceCalculator.isArEligible(invoice)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Customer credits cannot be applied to " + invoice.getStatus() + " invoices");
        }
        if (!credit.getCustomerId().equals(resolveCustomerId(invoice))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Credit " + creditId + " belongs to a different customer than invoice " + invoice.getInvoiceId());
        }

        BigDecimal balanceDue = invoiceBalanceCalculator.balanceDue(invoice);
        if (amount.compareTo(balanceDue) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Applied amount " + amount + " exceeds invoice " + invoice.getInvoiceId() + " balance due "
                            + balanceDue);
        }

        Instant now = Instant.now(clock);
        requirePostablePeriod(now);
        CustomerCreditTransaction transaction = recordDrawDown(
                credit,
                CustomerCreditTransactionType.APPLICATION,
                invoice.getInvoiceId(),
                amount,
                request.getRequestId(),
                currentUser);

        credit.setAppliedAmount(nullSafe(credit.getAppliedAmount()).add(amount));
        CustomerCredit saved = persistWithDerivedStatus(credit);

        enqueueReliefWorkItem(saved, transaction, now);

        log.info(
                "Applied customer credit {} to invoice {} | amount={} | openAmountAfter={} | requestId={}",
                creditId,
                invoice.getInvoiceId(),
                amount,
                saved.getOpenAmount(),
                request.getRequestId());

        return CustomerCreditTransactionResponse.builder()
                .creditTransactionId(transaction.getCreditTransactionId())
                .creditId(creditId)
                .transactionType(CustomerCreditTransactionType.APPLICATION)
                .invoiceId(invoice.getInvoiceId())
                .amount(amount)
                .currency(transaction.getCurrency())
                .requestId(request.getRequestId())
                .creditOpenAmountAfter(saved.getOpenAmount())
                .invoiceBalanceAfter(balanceDue.subtract(amount))
                .createdAt(now)
                .replayed(false)
                .build();
    }

    @Override
    @NonNull
    public CustomerCreditTransactionResponse refundCredit(
            @NonNull UUID creditId, @NonNull CustomerCreditRefundRequest request, @NonNull String currentUser) {

        BigDecimal amount = validateAmount(request.getAmount());

        CustomerCreditTransaction replay = creditTransactionRepository
                .findByRequestId(request.getRequestId())
                .orElse(null);
        if (replay != null) {
            requireReplayMatches(replay, creditId, CustomerCreditTransactionType.REFUND, null, amount);
            return replayResponse(replay, "refund");
        }

        CustomerCredit credit = fetchCredit(creditId);
        validateOpenAmount(credit, amount);

        Instant now = Instant.now(clock);
        requirePostablePeriod(now);
        CustomerCreditTransaction transaction = recordDrawDown(
                credit, CustomerCreditTransactionType.REFUND, null, amount, request.getRequestId(), currentUser);

        credit.setRefundedAmount(nullSafe(credit.getRefundedAmount()).add(amount));
        CustomerCredit saved = persistWithDerivedStatus(credit);

        enqueueReliefWorkItem(saved, transaction, now);

        log.info(
                "Refunded customer credit {} | amount={} | openAmountAfter={} | requestId={}",
                creditId,
                amount,
                saved.getOpenAmount(),
                request.getRequestId());

        return CustomerCreditTransactionResponse.builder()
                .creditTransactionId(transaction.getCreditTransactionId())
                .creditId(creditId)
                .transactionType(CustomerCreditTransactionType.REFUND)
                .invoiceId(null)
                .amount(amount)
                .currency(transaction.getCurrency())
                .requestId(request.getRequestId())
                .creditOpenAmountAfter(saved.getOpenAmount())
                .invoiceBalanceAfter(null)
                .createdAt(now)
                .replayed(false)
                .build();
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public CustomerCreditResponse getCredit(@NonNull UUID creditId) {
        return toResponse(fetchCredit(creditId));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public Page<CustomerCreditResponse> listCredits(
            @Nullable UUID customerId, @Nullable CustomerCreditStatus status, @NonNull Pageable pageable) {

        Page<CustomerCredit> credits;
        if (customerId != null && status != null) {
            credits = customerCreditRepository.findByCustomerIdAndStatus(customerId, status, pageable);
        } else if (customerId != null) {
            credits = customerCreditRepository.findByCustomerId(customerId, pageable);
        } else if (status != null) {
            credits = customerCreditRepository.findByStatus(status, pageable);
        } else {
            credits = customerCreditRepository.findAll(pageable);
        }
        return credits.map(this::toResponse);
    }

    // ------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------

    private CustomerCredit fetchCredit(UUID creditId) {
        return customerCreditRepository
                .findById(creditId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer credit not found: " + creditId));
    }

    /**
     * Load the invoice under a row-level write lock for the duration of this transaction.
     *
     * <p><b>Why a lock and not just the credit's {@code @Version}.</b> The version on
     * {@link CustomerCredit} serializes two draw-downs of the <em>same</em> credit; it does
     * nothing about two <em>different</em> credits drawn against the <em>same</em> invoice.
     * Without this lock, two concurrent applications of 100.00 against a 100.00 invoice both
     * read {@code balanceDue == 100.00} (neither sees the other's uncommitted draw-down),
     * both pass the balance gate, and both commit — each within its own credit's CHECK — so
     * AR is credited 200.00 against a 100.00 receivable and the invoice balance goes
     * negative. There is no reversal path for a credit application, so that is permanent.
     * Locking the invoice row makes the balance read and the draw-down insert atomic per
     * invoice.
     *
     * <p>Scope note: payment application and credit-memo issuance read the same derived
     * balance and are not yet serialized this way. That is a pre-existing exposure this
     * change does not widen (it adds the third writer, already guarded); tightening the
     * other two paths is tracked separately.
     */
    private ExtInvoice fetchInvoiceForUpdate(UUID invoiceId) {
        return extInvoiceRepository
                .findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Invoice " + invoiceId + " not found in the invoice replica"));
    }

    /**
     * Reject a missing or non-positive amount before anything else runs.
     *
     * <p>HTTP callers are already covered by {@code @NotNull} + {@code @DecimalMin("0.01")} on
     * the request DTOs, but {@link com.positivity.accounting.service.CustomerCreditService} is
     * in the module's public {@code service} package, so an in-process caller reaches this
     * method with bean validation never having run. A negative amount would otherwise pass the
     * open-amount gate ({@code -50 <= 100}), *increase* the credit's open amount, and enqueue a
     * relief work item that posts a negative debit — the exact shape
     * {@code chk_journal_entry_line_debit_xor_credit} rejects, surfacing as a 500 from the
     * outbox rather than a clean rejection here.
     */
    private BigDecimal validateAmount(@Nullable BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be a positive value; got " + amount);
        }
        return amount;
    }

    /**
     * A credit can only be drawn down for what it still owes. Rejecting here (rather than
     * letting the DB CHECK fire) keeps over-draw a 409 with an explanatory message, and the
     * {@code @Version} on the credit closes the read-check-write race between concurrent
     * draw-downs of the same credit.
     */
    private void validateOpenAmount(CustomerCredit credit, BigDecimal amount) {
        BigDecimal open = credit.getOpenAmount();
        if (amount.compareTo(open) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Amount " + amount + " exceeds the open amount " + open + " of credit " + credit.getCreditId());
        }
    }

    /**
     * Refuse to record a draw-down whose relief cannot post.
     *
     * <p>The relief work item carries the draw-down's business timestamp and reuses it as the
     * journal-entry transaction date on every retry — correct for period placement, but it
     * means a relief aimed at a CLOSED or hard-locked period retries against that same period
     * forever. The draw-down, meanwhile, has already committed: the credit reads consumed and
     * {@code InvoiceBalanceCalculator} already subtracts the application, while 2300 is never
     * debited. That is a permanent break of the very {@code Σ open credits == 2300 balance}
     * invariant this feature exists to establish.
     *
     * <p>So gate synchronously, mirroring {@code CreditMemoServiceImpl}, which posts inline and
     * therefore rejects a bad period before the memo is created. Callers get a 409 they can act
     * on instead of a silently stuck outbox row.
     */
    private void requirePostablePeriod(Instant drawDownAt) {
        if (!periodService.isPeriodOpen(LocalDate.ofInstant(drawDownAt, clock.getZone()))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Accounting period for " + LocalDate.ofInstant(drawDownAt, clock.getZone())
                            + " is not open; the customer-credit relief could not be posted, so the draw-down was "
                            + "not recorded");
        }
    }

    /**
     * A replayed {@code requestId} must describe the <em>same</em> operation it originally
     * recorded.
     *
     * <p>{@code request_id} is globally unique on {@code customer_credit_transaction}, not
     * scoped per credit or per operation. Without this check, reusing a key for a genuinely
     * different draw-down returns {@code 200} carrying the *original* transaction — so the
     * caller books a refund (or another credit's application) as complete when nothing was
     * recorded, no work item was enqueued, and no entry was posted, while also disclosing the
     * other credit's identifiers and amount. Treat a mismatch as the client error it is.
     */
    private void requireReplayMatches(
            CustomerCreditTransaction existing,
            UUID creditId,
            CustomerCreditTransactionType type,
            @Nullable UUID invoiceId,
            BigDecimal amount) {

        boolean matches = existing.getCreditId().equals(creditId)
                && existing.getTransactionType() == type
                && java.util.Objects.equals(existing.getInvoiceId(), invoiceId)
                && existing.getAmount().compareTo(amount) == 0;
        if (!matches) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Request id " + existing.getRequestId() + " was already used for a different customer-credit "
                            + "operation (credit " + existing.getCreditId() + ", " + existing.getTransactionType()
                            + ", amount " + existing.getAmount() + "); reusing it for a different draw-down is not "
                            + "an idempotent replay");
        }
    }

    /**
     * The replica's partyId is a free-form string (pos-invoice stores it that way); AR records
     * require a customer UUID. Mirrors {@code CreditMemoServiceImpl.resolveCustomerId}.
     */
    private UUID resolveCustomerId(ExtInvoice invoice) {
        String partyId = invoice.getPartyId();
        if (partyId == null || partyId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Invoice " + invoice.getInvoiceId() + " has no billed party; cannot apply a customer credit");
        }
        try {
            return UUID.fromString(partyId.trim());
        } catch (IllegalArgumentException _) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Invoice " + invoice.getInvoiceId() + " has a non-UUID party reference: " + partyId);
        }
    }

    private CustomerCreditTransaction recordDrawDown(
            CustomerCredit credit,
            CustomerCreditTransactionType type,
            @Nullable UUID invoiceId,
            BigDecimal amount,
            String requestId,
            String currentUser) {

        return creditTransactionRepository.save(CustomerCreditTransaction.builder()
                .creditId(credit.getCreditId())
                .transactionType(type)
                .invoiceId(invoiceId)
                .amount(amount)
                .currency(credit.getCurrency())
                .requestId(requestId)
                .traceId(credit.getTraceId())
                // createdAt is owned by @CreatedDate auditing (wired to the same injected Clock),
                // so setting it here would be silently overwritten on flush.
                .createdBy(currentUser)
                .build());
    }

    /**
     * Recompute the consumption state from the running totals, then persist.
     *
     * <p>Uses the <em>unrounded</em> residual so the status agrees with
     * {@code CustomerCreditRepository.sumOpenAmount()}, the query the AC-4 subledger↔GL invariant
     * is asserted on. Deriving from the cent-rounded value would let a credit with a sub-cent
     * residual read CONSUMED while the reconciliation still counted that residual.
     */
    private CustomerCredit persistWithDerivedStatus(CustomerCredit credit) {
        BigDecimal open = credit.getExactOpenAmount();
        BigDecimal consumed = nullSafe(credit.getAppliedAmount()).add(nullSafe(credit.getRefundedAmount()));
        if (open.signum() <= 0) {
            credit.setStatus(CustomerCreditStatus.CONSUMED);
        } else if (consumed.signum() > 0) {
            credit.setStatus(CustomerCreditStatus.PARTIALLY_CONSUMED);
        } else {
            credit.setStatus(CustomerCreditStatus.AVAILABLE);
        }
        return customerCreditRepository.save(credit);
    }

    /**
     * Enqueue the relieving GL work item inside this transaction ({@code saveToOutbox} uses
     * MANDATORY propagation), so the ledger entry is enqueued iff the draw-down committed.
     */
    private void enqueueReliefWorkItem(
            CustomerCredit credit, CustomerCreditTransaction transaction, Instant reliefTimestamp) {

        CustomerCreditReliefGLPostingEvent event = CustomerCreditReliefGLPostingEvent.builder()
                .eventId(UUIDv7Generator.generate())
                .requestId(transaction.getRequestId())
                .creditTransactionId(transaction.getCreditTransactionId())
                .creditId(credit.getCreditId())
                .transactionType(transaction.getTransactionType())
                .invoiceId(transaction.getInvoiceId())
                .customerId(credit.getCustomerId())
                .currency(transaction.getCurrency())
                .amount(transaction.getAmount())
                .reliefTimestamp(reliefTimestamp)
                .build();

        outboxService.saveToOutbox(
                event.getEventId(),
                "CustomerCreditRelief",
                credit.getCreditId(),
                event.getClass().getName(),
                event);

        log.info(
                "Customer credit relief GL posting work item persisted to outbox | requestId={} | eventId={} "
                        + "| creditId={} | type={} | amount={}",
                transaction.getRequestId(),
                event.getEventId(),
                credit.getCreditId(),
                transaction.getTransactionType(),
                transaction.getAmount());
    }

    /**
     * Idempotent replay: the request id was already processed, so return the original draw-down
     * with the credit's <em>current</em> open amount and enqueue nothing. Relieving again would
     * double-debit the 2300 liability.
     */
    private CustomerCreditTransactionResponse replayResponse(CustomerCreditTransaction existing, String operation) {
        log.info(
                "Customer credit {} replay for already-processed requestId={} | creditTransactionId={}",
                operation,
                existing.getRequestId(),
                existing.getCreditTransactionId());

        CustomerCredit credit = fetchCredit(existing.getCreditId());
        BigDecimal invoiceBalanceAfter = existing.getInvoiceId() == null
                ? null
                : invoiceBalanceCalculator
                        .findInvoice(existing.getInvoiceId())
                        .map(invoiceBalanceCalculator::balanceDue)
                        .orElse(null);

        return CustomerCreditTransactionResponse.builder()
                .creditTransactionId(existing.getCreditTransactionId())
                .creditId(existing.getCreditId())
                .transactionType(existing.getTransactionType())
                .invoiceId(existing.getInvoiceId())
                .amount(existing.getAmount())
                .currency(existing.getCurrency())
                .requestId(existing.getRequestId())
                .creditOpenAmountAfter(credit.getOpenAmount())
                .invoiceBalanceAfter(invoiceBalanceAfter)
                .createdAt(existing.getCreatedAt())
                .replayed(true)
                .build();
    }

    private CustomerCreditResponse toResponse(CustomerCredit credit) {
        return CustomerCreditResponse.builder()
                .creditId(credit.getCreditId())
                .customerId(credit.getCustomerId())
                .currency(credit.getCurrency())
                .amount(credit.getAmount())
                .appliedAmount(nullSafe(credit.getAppliedAmount()))
                .refundedAmount(nullSafe(credit.getRefundedAmount()))
                .openAmount(credit.getOpenAmount())
                .status(credit.getStatus())
                .sourcePaymentId(credit.getSourcePaymentId())
                .createdAt(credit.getCreatedAt())
                .build();
    }

    private static BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
