package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.CreditMemoGLConfig;
import com.positivity.accounting.internal.dto.CreateCreditMemoRequest;
import com.positivity.accounting.internal.dto.CreditMemoResponse;
import com.positivity.accounting.internal.dto.ResolvedDisplayReference;
import com.positivity.accounting.internal.entity.AccountingSequence;
import com.positivity.accounting.internal.entity.CreditMemo;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.ExtInvoiceTax;
import com.positivity.accounting.internal.enums.CreditMemoStatus;
import com.positivity.accounting.internal.enums.DisplayReferenceType;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.CreditMemoRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceTaxRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Service for managing Credit Memos (AR corrections).
 *
 * Credit Memos reduce Accounts Receivable by reversing invoice charges,
 * typically for returned goods, pricing errors, or service credits.
 *
 * Business Rules (from Issue #131):
 * - Credit Memo must reference a finalized invoice
 * - Credit amount cannot exceed invoice outstanding balance
 * - Reason code is mandatory for audit trail
 * - GL entries must be balanced (debit revenue + debit tax = credit AR)
 * - Prior period adjustments: post to current period with flag
 * - No approval workflow for v1.0
 *
 * Phase 2.1: Full integration with Invoice, GL posting, and Accounting period
 * services.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/131">Issue
 *      #131</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CreditMemoServiceImpl implements CreditMemoService {
    private final Clock clock;

    private static final String DEFAULT_CURRENCY = "USD";

    /** Currency scale (2dp) applied to reversed tax amounts. */
    private static final int CURRENCY_SCALE = 2;

    /** Precision of the intermediate credit ratio; only the final tax amount is currency-rounded. */
    private static final int RATIO_SCALE = 10;

    private static final BigDecimal ZERO_TAX = new BigDecimal("0.00");

    /** Sequence-scope prefix for the per-month {@code credit_memo.creditMemoReference} counter. */
    private static final String CREDIT_MEMO_REFERENCE_SCOPE_PREFIX = "CM-";

    private final CreditMemoRepository creditMemoRepository;
    private final ExtInvoiceTaxRepository extInvoiceTaxRepository;
    private final AccountingSequenceRepository sequenceRepository;
    private final AccountingSequenceProvisioner sequenceProvisioner;
    private final DisplayReferenceResolver displayReferenceResolver;
    private final CreditMemoTaxAttributionService creditMemoTaxAttributionService;
    private final InvoiceBalanceCalculator invoiceBalanceCalculator;
    private final GLPostingService glPostingService;
    private final AccountingPeriodService periodService;
    private final CreditMemoGLConfig glConfig;

    /**
     * Create a Credit Memo to reverse invoice charges.
     *
     * Phase 2.1 implementation with full integrations:
     * - Invoice validation via the ext_invoice replica (ADR-0044)
     * - GL posting via GLPostingService
     * - Prior period adjustment logic via AccountingPeriodService
     * - Invoice balance derived from accounting's own records (ADR-0044 R6)
     *
     * @param request     Credit Memo creation request
     * @param currentUser User creating the Credit Memo
     * @return Created Credit Memo details
     * @throws ResponseStatusException 404 if invoice not found, 409 if business
     *                                 rules violated
     */
    @Override
    public CreditMemoResponse createCreditMemo(@NonNull CreateCreditMemoRequest request, @NonNull String currentUser) {

        log.info(
                "Creating Credit Memo for invoice {} with amount {}, reason: {}",
                request.getOriginalInvoiceId(),
                request.getCreditAmount(),
                request.getReasonCode());

        ExtInvoice invoice = fetchInvoice(request.getOriginalInvoiceId());
        validateInvoiceStatus(invoice);

        BigDecimal balanceDue = invoiceBalanceCalculator.balanceDue(invoice);
        validateBalanceDue(invoice, balanceDue);

        CreditAmountCalculation creditCalculation = calculateCreditAmounts(request, invoice);
        validateCreditDoesNotExceedBalance(request, balanceDue, creditCalculation);

        PriorPeriodInfo priorPeriodInfo = determinePriorPeriodInfo(invoice);

        CreditMemo creditMemo = creditMemoRepository.save(
                buildCreditMemo(request, currentUser, invoice, creditCalculation.taxReversed(), priorPeriodInfo));

        log.info(
                "Created Credit Memo {} with total amount {}",
                creditMemo.getCreditMemoId(),
                creditMemo.calculateTotalAmount());

        // Freeze the per-jurisdiction attribution of the reversed tax alongside the memo
        // (issue #996), so the T8 liability report reads an actual credit-side breakdown
        // instead of re-deriving a pro-rata approximation on every run.
        creditMemoTaxAttributionService.attribute(
                creditMemo.getCreditMemoId(),
                invoice.getInvoiceId(),
                creditCalculation.taxReversed(),
                creditCalculation.finalCredit());

        postGlEntries(creditMemo, request, creditCalculation.taxReversed(), priorPeriodInfo);

        // Display reference assigned last, deliberately (issue #1779, matching
        // EventIngestionServiceImpl.submitEvent's placement for #1680). The scope row is read
        // FOR UPDATE and held to commit, so assigning it inside buildCreditMemo would serialize
        // every concurrent memo in the month behind this memo's tax attribution and GL posting
        // rather than behind a counter increment. It also keeps this transaction from holding
        // CM-{YYYYMM} while postGlEntries acquires JE-{YYYYMM}, so the two scopes are never held
        // at once. Set on the still-managed entity and flushed by dirty checking; no second save
        // is needed since this method is transactional. Placed after the GL post, which throws on
        // failure, so a rolled-back memo never consumes a number.
        assignCreditMemoReference(creditMemo);

        // The balance is derived from accounting's own records (ADR-0044 R6): the POSTED memo
        // saved above already reduces it — nothing to tell pos-invoice.
        BigDecimal balanceAfter = balanceDue.subtract(creditMemo.calculateTotalAmount());

        log.info(
                "Applied Credit Memo {} to invoice {}: balance {} -> {}",
                creditMemo.getCreditMemoId(),
                invoice.getInvoiceId(),
                balanceDue,
                balanceAfter);

        // Build and return response
        return buildResponse(creditMemo, balanceAfter);
    }

    private ExtInvoice fetchInvoice(UUID invoiceId) {
        return invoiceBalanceCalculator
                .findInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Invoice " + invoiceId + " not found in the invoice replica"));
    }

    private void validateInvoiceStatus(ExtInvoice invoice) {
        if (!invoiceBalanceCalculator.isArEligible(invoice)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Credit memos cannot be issued against " + invoice.getStatus() + " invoices");
        }
    }

    private void validateBalanceDue(ExtInvoice invoice, BigDecimal balanceDue) {
        if (balanceDue.compareTo(BigDecimal.ZERO) > 0) {
            return;
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Invoice has no remaining balance to credit. Balance due: " + balanceDue + ", Total amount: "
                        + invoice.getTotal());
    }

    private CreditAmountCalculation calculateCreditAmounts(CreateCreditMemoRequest request, ExtInvoice invoice) {
        TaxReversal reversal = calculateTaxReversed(request.getCreditAmount(), invoice);
        BigDecimal totalCreditAmount = request.getCreditAmount().add(reversal.amount());
        return new CreditAmountCalculation(reversal.amount(), totalCreditAmount, reversal.finalCredit());
    }

    /**
     * Derive the tax to reverse from the invoice's STORED, frozen tax (issue #953 interim scalar;
     * D1 breakdown-upgrade, Odoo parity decision D-4). Tax is frozen at invoice finalization and is
     * never recomputed from rates at credit time (rate-drift protection).
     *
     * <p>Source of the total tax (D1 upgrade): the authoritative per-line × per-jurisdiction
     * {@code ext_invoice_tax} replica populated by tax-plan T5 ({@code Σ taxAmount} of its rows).
     * When no replica rows exist — a pre-T5 invoice, or one whose breakdown was never emitted — it
     * falls back to the scalar {@code ExtInvoice.tax} rollup (strictly better than the retired 10%
     * heuristic, and equal to the breakdown sum by the T5 {@code scalar == Σ breakdown} invariant).
     * Per D-4/D2 the GL posting still reverses a single sales-tax-payable account; the jurisdiction
     * detail is report-time aggregation (tax-plan T8), not a per-jurisdiction credit posting.
     *
     * <p>The credit amount is the revenue (pre-tax) portion being reversed, so the pro-rating
     * ratio is {@code creditAmount / net} where {@code net = total − tax}, and
     * {@code taxReversed = round(tax × ratio)} HALF_UP at currency scale.
     *
     * <p>Final-credit residual correction (Odoo last-partial pattern): when this credit consumes
     * the invoice's remaining net amount, the reversal is {@code tax − previouslyReversed} so the
     * cumulative reversals across all POSTED memos sum exactly to the stored tax (e.g. 35.59
     * split 50/50 posts 17.80 then 17.79).
     */
    @NonNull
    private TaxReversal calculateTaxReversed(@NonNull BigDecimal creditAmount, @NonNull ExtInvoice invoice) {
        BigDecimal totalTax = resolveFrozenTax(invoice);
        if (totalTax.signum() <= 0) {
            return new TaxReversal(ZERO_TAX, false);
        }
        BigDecimal total = invoice.getTotal() == null ? BigDecimal.ZERO : invoice.getTotal();
        BigDecimal netAmount = total.subtract(totalTax);

        BigDecimal previouslyCreditedNet = creditMemoRepository.sumCreditAmountByInvoiceIdAndStatus(
                invoice.getInvoiceId(), CreditMemoStatus.POSTED);
        BigDecimal remainingNet = netAmount.subtract(previouslyCreditedNet);

        if (creditAmount.compareTo(remainingNet) >= 0) {
            // Final line: reverse the residual so cumulative reversals sum exactly to the
            // stored tax. A non-positive residual means the tax is already fully reversed.
            BigDecimal previouslyReversed = creditMemoRepository.sumTaxReversedAmountByInvoiceIdAndStatus(
                    invoice.getInvoiceId(), CreditMemoStatus.POSTED);
            BigDecimal residual = totalTax.subtract(previouslyReversed);
            // The final-credit flag drives the per-jurisdiction residual correction in
            // CreditMemoTaxAttributionService (issue #996) — the jurisdiction-level
            // counterpart of the scalar residual computed here.
            return new TaxReversal(residual.signum() > 0 ? residual : ZERO_TAX, true);
        }

        // remainingNet > creditAmount > 0 implies netAmount > 0: division is safe.
        BigDecimal creditRatio = creditAmount.divide(netAmount, RATIO_SCALE, RoundingMode.HALF_UP);
        return new TaxReversal(totalTax.multiply(creditRatio).setScale(CURRENCY_SCALE, RoundingMode.HALF_UP), false);
    }

    /**
     * The frozen total tax to reverse, at currency scale (D1 breakdown-upgrade). Prefers the
     * authoritative {@code ext_invoice_tax} replica ({@code Σ taxAmount}); falls back to the scalar
     * {@code ExtInvoice.tax} rollup when the invoice has no breakdown rows (pre-T5 invoices). A
     * non-positive result (untaxed / fully exempt / absent) reverses no tax.
     */
    @NonNull
    private BigDecimal resolveFrozenTax(@NonNull ExtInvoice invoice) {
        List<ExtInvoiceTax> breakdown = extInvoiceTaxRepository.findByInvoiceId(invoice.getInvoiceId());
        if (breakdown != null && !breakdown.isEmpty()) {
            return breakdown.stream()
                    .map(ExtInvoiceTax::getTaxAmount)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal storedTax = invoice.getTax();
        return storedTax == null ? ZERO_TAX : storedTax.setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);
    }

    private void validateCreditDoesNotExceedBalance(
            CreateCreditMemoRequest request, BigDecimal balanceDue, CreditAmountCalculation creditCalculation) {
        if (creditCalculation.totalCreditAmount().compareTo(balanceDue) <= 0) {
            return;
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Total credit amount " + creditCalculation.totalCreditAmount()
                        + " (credit: " + request.getCreditAmount() + " + tax: " + creditCalculation.taxReversed()
                        + ")"
                        + " exceeds invoice outstanding balance " + balanceDue);
    }

    private PriorPeriodInfo determinePriorPeriodInfo(ExtInvoice invoice) {
        Instant invoiceDate =
                invoice.getFinalizedAt() != null ? invoice.getFinalizedAt() : invoice.getInvoiceCreatedAt();
        if (invoiceDate == null) {
            return new PriorPeriodInfo(false, null);
        }
        boolean isPriorPeriod = periodService.isPriorPeriod(invoiceDate);
        if (!isPriorPeriod) {
            return new PriorPeriodInfo(false, null);
        }
        String originalPeriodId = periodService.getPeriodIdForDate(invoiceDate);
        log.info(
                "Credit Memo is a prior period adjustment: invoice period {}, current period {}",
                originalPeriodId,
                periodService.getCurrentPeriodId());
        return new PriorPeriodInfo(true, originalPeriodId);
    }

    private CreditMemo buildCreditMemo(
            CreateCreditMemoRequest request,
            String currentUser,
            ExtInvoice invoice,
            BigDecimal taxReversed,
            PriorPeriodInfo priorPeriodInfo) {
        CreditMemo creditMemo = new CreditMemo();
        creditMemo.setOriginalInvoiceId(request.getOriginalInvoiceId());
        creditMemo.setCustomerId(resolveCustomerId(invoice));
        creditMemo.setCreditAmount(request.getCreditAmount());
        creditMemo.setTaxAmountReversed(taxReversed);
        creditMemo.setReasonCode(request.getReasonCode());
        creditMemo.setJustificationNote(request.getJustificationNote());
        creditMemo.setStatus(CreditMemoStatus.POSTED);
        creditMemo.setCreatedByUserId(currentUser);
        creditMemo.setPriorPeriodAdjustment(priorPeriodInfo.priorPeriod());
        creditMemo.setOriginalPeriodId(priorPeriodInfo.originalPeriodId());
        // pos-invoice carries no currency (single-currency platform); AR records default USD.
        creditMemo.setCurrency(DEFAULT_CURRENCY);
        // Set explicitly from the injected clock rather than leaving it to @PrePersist's
        // TimeSource read (ADR-0013): the display reference assigned below buckets the memo by
        // its creation month, and the two must agree on the same instant or a memo created near
        // a month boundary could be numbered into a month its own timestamp does not fall in.
        creditMemo.setCreationTimestamp(Instant.now(clock));
        creditMemo.setPostedTimestamp(Instant.now(clock));
        return creditMemo;
    }

    /**
     * The replica's partyId is a free-form string (pos-invoice stores it that way); AR records
     * require a customer UUID. Reject invoices whose party reference is missing or malformed.
     */
    private UUID resolveCustomerId(ExtInvoice invoice) {
        String partyId = invoice.getPartyId();
        if (partyId == null || partyId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Invoice " + invoice.getInvoiceId() + " has no billed party; cannot issue a credit memo");
        }
        try {
            return UUID.fromString(partyId.trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Invoice " + invoice.getInvoiceId() + " has a non-UUID party reference: " + partyId);
        }
    }

    private void postGlEntries(
            CreditMemo creditMemo,
            CreateCreditMemoRequest request,
            BigDecimal taxReversed,
            PriorPeriodInfo priorPeriodInfo) {
        try {
            glPostingService.postCreditMemoReversal(
                    creditMemo.getCreditMemoId(),
                    glConfig.getRevenueAccountId(),
                    glConfig.getTaxPayableAccountId(),
                    glConfig.getArAccountId(),
                    request.getCreditAmount(),
                    taxReversed,
                    "Credit Memo " + creditMemo.getCreditMemoId() + " - " + request.getReasonCode(),
                    priorPeriodInfo.priorPeriod(),
                    priorPeriodInfo.originalPeriodId());
        } catch (Exception e) {
            log.error(
                    "Failed to post GL entries for Credit Memo {}: {}",
                    creditMemo.getCreditMemoId(),
                    e.getMessage(),
                    e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Failed to post GL entries: " + e.getMessage());
        }
    }

    /**
     * List Credit Memos with optional filters.
     *
     * @param customerId        Optional customer filter
     * @param originalInvoiceId Optional invoice filter
     * @param status            Optional status filter
     * @param pageable          Pagination parameters
     * @return Paginated Credit Memo list
     */
    @Override
    @Transactional(readOnly = true)
    public Page<CreditMemoResponse> listCreditMemos(
            UUID customerId, UUID originalInvoiceId, CreditMemoStatus status, @NonNull Pageable pageable) {

        log.debug(
                "Listing Credit Memos: customerId={}, invoiceId={}, status={}", customerId, originalInvoiceId, status);

        Page<CreditMemo> creditMemos;

        if (customerId != null) {
            creditMemos = creditMemoRepository.findByCustomerId(customerId, pageable);
        } else if (originalInvoiceId != null) {
            creditMemos = creditMemoRepository.findByOriginalInvoiceId(originalInvoiceId, pageable);
        } else if (status != null) {
            creditMemos = creditMemoRepository.findByStatus(status, pageable);
        } else {
            creditMemos = creditMemoRepository.findAll(pageable);
        }

        // Resolve display values once for the whole page (issue #1779): two IN queries, not two
        // per row. Balance stays unavailable in the list view, as before.
        DisplayLookup display = resolveDisplayValues(creditMemos.getContent());
        return creditMemos.map(cm -> buildResponse(cm, null, display));
    }

    /**
     * Get a Credit Memo by ID.
     *
     * @param creditMemoId Credit Memo identifier
     * @return Credit Memo details
     * @throws ResponseStatusException 404 if Credit Memo not found
     */
    @Override
    @Transactional(readOnly = true)
    public CreditMemoResponse getCreditMemo(@NonNull UUID creditMemoId) {
        log.debug("Fetching Credit Memo {}", creditMemoId);

        CreditMemo creditMemo = creditMemoRepository
                .findById(creditMemoId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Credit Memo not found: " + creditMemoId));

        // Balance derived from the ext_invoice replica + accounting's own records (ADR-0044).
        BigDecimal invoiceBalanceAfter = invoiceBalanceCalculator
                .findInvoice(creditMemo.getOriginalInvoiceId())
                .map(invoiceBalanceCalculator::balanceDue)
                .orElseGet(() -> {
                    log.warn(
                            "Invoice {} not in replica while resolving balance for Credit Memo {}",
                            creditMemo.getOriginalInvoiceId(),
                            creditMemoId);
                    return BigDecimal.ZERO;
                });

        return buildResponse(creditMemo, invoiceBalanceAfter);
    }

    /**
     * Void a POSTED Credit Memo (issue #997 symmetry). The memo's posting-period T8 contribution
     * and its original journal entry are never touched; the void posts the mirror entry
     * ({@code Dr AR / Cr Revenue + Cr Sales-Tax Payable}) dated now, and the T8 report restores
     * the reversed tax in the void's period. Invoice balance restoration is automatic: every
     * balance sum counts POSTED memos only.
     */
    @Override
    public CreditMemoResponse voidCreditMemo(
            @NonNull UUID creditMemoId, @NonNull String voidReason, @NonNull String currentUser) {

        // Locked read (SELECT ... FOR UPDATE): two concurrent voids serialize here, so the
        // second sees VOIDED and gets the 409 instead of double-posting the mirror GL entry.
        CreditMemo creditMemo = creditMemoRepository
                .findWithLockByCreditMemoId(creditMemoId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Credit Memo not found: " + creditMemoId));

        if (creditMemo.getStatus() != CreditMemoStatus.POSTED) {
            String detail = creditMemo.getStatus() == CreditMemoStatus.APPLIED
                    ? "it has been consumed (APPLIED); handle through the customer-credit lifecycle"
                    : "current status is " + creditMemo.getStatus();
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Credit Memo " + creditMemoId + " cannot be voided: " + detail);
        }

        creditMemo.setStatus(CreditMemoStatus.VOIDED);
        creditMemo.setVoidedTimestamp(Instant.now(clock));
        creditMemo.setVoidedByUserId(currentUser);
        creditMemo.setVoidReason(voidReason);
        creditMemoRepository.save(creditMemo);

        try {
            glPostingService.postCreditMemoVoid(
                    creditMemo.getCreditMemoId(),
                    glConfig.getRevenueAccountId(),
                    glConfig.getTaxPayableAccountId(),
                    glConfig.getArAccountId(),
                    creditMemo.getCreditAmount(),
                    creditMemo.getTaxAmountReversed(),
                    "Void Credit Memo " + creditMemo.getCreditMemoId() + " - " + voidReason);
        } catch (Exception e) {
            log.error(
                    "Failed to post VOID GL entries for Credit Memo {}: {}",
                    creditMemo.getCreditMemoId(),
                    e.getMessage(),
                    e);
            // Generic message only — the cause is logged above; raw exception text can leak
            // internal details (DB messages, class names) to API callers.
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to post void GL entries for credit memo " + creditMemo.getCreditMemoId());
        }

        BigDecimal balanceAfter = invoiceBalanceCalculator
                .findInvoice(creditMemo.getOriginalInvoiceId())
                .map(invoiceBalanceCalculator::balanceDue)
                .orElse(BigDecimal.ZERO);

        log.info(
                "Voided Credit Memo {} (invoice {}): AR restored by {}, tax liability restored by {}",
                creditMemo.getCreditMemoId(),
                creditMemo.getOriginalInvoiceId(),
                creditMemo.calculateTotalAmount(),
                creditMemo.getTaxAmountReversed());

        return buildResponse(creditMemo, balanceAfter);
    }

    // ===== DISPLAY REFERENCE ASSIGNMENT (issue #1779) =====

    /**
     * Assigns the display reference {@code CM-{YYYYMM}-{seq}} from the per-month
     * {@code accounting_sequence} counter, reusing the same counter table and
     * {@link AccountingSequenceProvisioner} bootstrap machinery as
     * {@code EventIngestionServiceImpl.assignEventReference} (#1680) and
     * {@code JournalEntryServiceImpl.assignEntryNumber} (#942).
     *
     * <p>Scope key is {@code CM-{YYYYMM}} derived from the memo's creation timestamp, so the
     * reference always matches the creation month shown in the UI. The counter row is read under
     * {@code FOR UPDATE} and incremented inside the caller's transaction ({@code createCreditMemo}
     * is transactional), so a rollback of the creation rolls the increment back with it and the
     * number is never consumed.
     *
     * @param creditMemo the memo being built; its {@code creditMemoReference} is set as a side
     *                   effect
     */
    private void assignCreditMemoReference(CreditMemo creditMemo) {
        String scopeKey = creditMemoReferenceScopeKey(creditMemo.getCreationTimestamp());
        AccountingSequence sequence = sequenceRepository
                .findByScopeKey(scopeKey)
                .orElseGet(() -> provisionAndRelockCreditMemoReference(scopeKey));
        long assigned = sequence.getNextValue();
        sequence.setNextValue(assigned + 1);
        creditMemo.setCreditMemoReference(scopeKey + "-" + assigned);
    }

    /**
     * First use of a month scope: bootstrap the counter row in an isolated transaction
     * ({@link AccountingSequenceProvisioner}), then lock it in the current transaction. A
     * concurrent bootstrapper losing the unique-key race falls through to the locked re-read of
     * the winner's committed row.
     */
    private AccountingSequence provisionAndRelockCreditMemoReference(String scopeKey) {
        try {
            sequenceProvisioner.provision(scopeKey);
        } catch (DataIntegrityViolationException raceLost) {
            log.debug("Lost accounting_sequence bootstrap race for scope {}; re-reading winner's row", scopeKey);
        }
        return sequenceRepository
                .findByScopeKey(scopeKey)
                .orElseThrow(() ->
                        new IllegalStateException("accounting_sequence row missing after bootstrap: " + scopeKey));
    }

    /**
     * Sequence scope key {@code CM-{YYYYMM}} for a creation instant, interpreted in UTC (this
     * module's house convention for Instant &lt;-&gt; calendar conversions, and what
     * {@code V35__credit_memo_reference.sql} backfilled existing rows with).
     */
    private static String creditMemoReferenceScopeKey(Instant creationTimestamp) {
        ZonedDateTime created = creationTimestamp.atZone(ZoneOffset.UTC);
        return String.format(
                "%s%04d%02d", CREDIT_MEMO_REFERENCE_SCOPE_PREFIX, created.getYear(), created.getMonthValue());
    }

    /**
     * Batch-resolve the invoice and customer display values for a set of memos (issue #1779) —
     * two {@code IN} queries per response regardless of page size, never one lookup per row.
     */
    private DisplayLookup resolveDisplayValues(Collection<CreditMemo> creditMemos) {
        return new DisplayLookup(
                displayReferenceResolver.resolve(
                        DisplayReferenceType.INVOICE,
                        creditMemos.stream()
                                .map(CreditMemo::getOriginalInvoiceId)
                                .toList()),
                displayReferenceResolver.resolve(
                        DisplayReferenceType.CUSTOMER,
                        creditMemos.stream().map(CreditMemo::getCustomerId).toList()));
    }

    /**
     * Display values for one page of memos, keyed by the identifier they belong to. A reference
     * accounting could not resolve is simply absent, and reads back as
     * {@link ResolvedDisplayReference#EMPTY} — so the response carries nulls, never a UUID
     * rendered as display text.
     */
    private record DisplayLookup(
            Map<UUID, ResolvedDisplayReference> invoices, Map<UUID, ResolvedDisplayReference> customers) {

        ResolvedDisplayReference invoice(UUID invoiceId) {
            return invoiceId == null
                    ? ResolvedDisplayReference.EMPTY
                    : invoices.getOrDefault(invoiceId, ResolvedDisplayReference.EMPTY);
        }

        ResolvedDisplayReference customer(UUID customerId) {
            return customerId == null
                    ? ResolvedDisplayReference.EMPTY
                    : customers.getOrDefault(customerId, ResolvedDisplayReference.EMPTY);
        }
    }

    /**
     * Build CreditMemoResponse from entity, resolving this memo's display values on its own.
     * List responses must use the batch overload instead.
     */
    private CreditMemoResponse buildResponse(CreditMemo creditMemo, BigDecimal invoiceBalanceAfter) {
        return buildResponse(creditMemo, invoiceBalanceAfter, resolveDisplayValues(List.of(creditMemo)));
    }

    /**
     * Build CreditMemoResponse from entity against display values already resolved for the whole
     * page (issue #1779).
     */
    private CreditMemoResponse buildResponse(
            CreditMemo creditMemo, BigDecimal invoiceBalanceAfter, DisplayLookup display) {
        ResolvedDisplayReference invoiceDisplay = display.invoice(creditMemo.getOriginalInvoiceId());
        ResolvedDisplayReference customerDisplay = display.customer(creditMemo.getCustomerId());

        CreditMemoResponse response = new CreditMemoResponse();
        response.setCreditMemoId(creditMemo.getCreditMemoId());
        response.setCreditMemoReference(creditMemo.getCreditMemoReference());
        response.setOriginalInvoiceId(creditMemo.getOriginalInvoiceId());
        response.setOriginalInvoiceReference(invoiceDisplay.displayReference());
        response.setCustomerDisplayName(customerDisplay.displayName());
        response.setCustomerReference(customerDisplay.displayReference());
        response.setCustomerId(creditMemo.getCustomerId());
        response.setCreditAmount(creditMemo.getCreditAmount());
        response.setTaxAmountReversed(creditMemo.getTaxAmountReversed());
        response.setTotalAmount(creditMemo.calculateTotalAmount());
        response.setReasonCode(creditMemo.getReasonCode());
        response.setJustificationNote(creditMemo.getJustificationNote());
        response.setStatus(creditMemo.getStatus());
        response.setCreationTimestamp(creditMemo.getCreationTimestamp());
        response.setPostedTimestamp(creditMemo.getPostedTimestamp());
        response.setCreatedByUserId(creditMemo.getCreatedByUserId());
        response.setPriorPeriodAdjustment(creditMemo.isPriorPeriodAdjustment());
        response.setOriginalPeriodId(creditMemo.getOriginalPeriodId());
        response.setCurrency(creditMemo.getCurrency());
        response.setVoidedTimestamp(creditMemo.getVoidedTimestamp());
        response.setVoidedByUserId(creditMemo.getVoidedByUserId());
        response.setVoidReason(creditMemo.getVoidReason());
        response.setInvoiceBalanceAfter(invoiceBalanceAfter);
        return response;
    }

    /**
     * The credit's monetary split.
     *
     * @param taxReversed       tax reversed by this credit (frozen, never recomputed from rates)
     * @param totalCreditAmount revenue credited + tax reversed
     * @param finalCredit       true when this credit consumes the invoice's remaining net amount,
     *                          so both the scalar and the per-jurisdiction reversal (#996) carry
     *                          the residual rather than a pro-rata share
     */
    public record CreditAmountCalculation(BigDecimal taxReversed, BigDecimal totalCreditAmount, boolean finalCredit) {}

    /** Result of the frozen-tax reversal calculation: the amount plus whether it is the final credit. */
    private record TaxReversal(BigDecimal amount, boolean finalCredit) {}

    public record PriorPeriodInfo(boolean priorPeriod, String originalPeriodId) {}
}
