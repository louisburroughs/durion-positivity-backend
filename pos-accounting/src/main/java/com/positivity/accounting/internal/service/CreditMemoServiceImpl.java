package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.CreditMemoGLConfig;
import com.positivity.accounting.internal.dto.CreateCreditMemoRequest;
import com.positivity.accounting.internal.dto.CreditMemoResponse;
import com.positivity.accounting.internal.entity.CreditMemo;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.enums.CreditMemoStatus;
import com.positivity.accounting.internal.repository.CreditMemoRepository;
import com.positivity.accounting.service.AccountingPeriodService;
import com.positivity.accounting.service.CreditMemoService;
import com.positivity.accounting.service.GLPostingService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
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

    private final CreditMemoRepository creditMemoRepository;
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

        CreditAmountCalculation creditCalculation = calculateCreditAmounts(request, balanceDue);
        validateCreditDoesNotExceedBalance(request, balanceDue, creditCalculation);

        PriorPeriodInfo priorPeriodInfo = determinePriorPeriodInfo(invoice);

        CreditMemo creditMemo = creditMemoRepository.save(
                buildCreditMemo(request, currentUser, invoice, creditCalculation.taxReversed(), priorPeriodInfo));

        log.info(
                "Created Credit Memo {} with total amount {}",
                creditMemo.getCreditMemoId(),
                creditMemo.calculateTotalAmount());

        postGlEntries(creditMemo, request, creditCalculation.taxReversed(), priorPeriodInfo);

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

    private CreditAmountCalculation calculateCreditAmounts(CreateCreditMemoRequest request, BigDecimal balanceDue) {
        BigDecimal taxAmount = balanceDue.multiply(new BigDecimal("0.10"));
        BigDecimal creditRatio = request.getCreditAmount().divide(balanceDue, 4, RoundingMode.HALF_UP);
        BigDecimal taxReversed = taxAmount.multiply(creditRatio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalCreditAmount = request.getCreditAmount().add(taxReversed);
        return new CreditAmountCalculation(taxReversed, totalCreditAmount);
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

        return creditMemos.map(cm -> buildResponse(cm, null)); // Balance unavailable in list view
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
     * Build CreditMemoResponse from entity.
     */
    private CreditMemoResponse buildResponse(CreditMemo creditMemo, BigDecimal invoiceBalanceAfter) {
        CreditMemoResponse response = new CreditMemoResponse();
        response.setCreditMemoId(creditMemo.getCreditMemoId());
        response.setOriginalInvoiceId(creditMemo.getOriginalInvoiceId());
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
        response.setInvoiceBalanceAfter(invoiceBalanceAfter);
        return response;
    }

    public record CreditAmountCalculation(BigDecimal taxReversed, BigDecimal totalCreditAmount) {}

    public record PriorPeriodInfo(boolean priorPeriod, String originalPeriodId) {}
}
