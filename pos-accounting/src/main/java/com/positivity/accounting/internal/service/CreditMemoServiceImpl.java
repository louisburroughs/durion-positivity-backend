package com.positivity.accounting.internal.service;

import java.time.Clock;

import com.positivity.accounting.internal.client.InvoiceServiceClient;
import com.positivity.accounting.internal.client.InvoiceServiceException;
import com.positivity.accounting.internal.config.CreditMemoGLConfig;
import com.positivity.accounting.internal.dto.ApplyCreditMemoRequest;
import com.positivity.accounting.internal.dto.ApplyCreditMemoResponse;
import com.positivity.accounting.internal.dto.CreateCreditMemoRequest;
import com.positivity.accounting.internal.dto.CreditMemoResponse;
import com.positivity.accounting.internal.dto.InvoiceDetails;
import com.positivity.accounting.internal.entity.CreditMemo;
import com.positivity.accounting.internal.enums.CreditMemoStatus;
import com.positivity.accounting.internal.enums.InvoiceStatus;
import com.positivity.accounting.internal.repository.CreditMemoRepository;
import com.positivity.accounting.service.AccountingPeriodService;
import com.positivity.accounting.service.CreditMemoService;
import com.positivity.accounting.service.GLPostingService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

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


    private final CreditMemoRepository creditMemoRepository;
    private final InvoiceServiceClient invoiceServiceClient;
    private final GLPostingService glPostingService;
    private final AccountingPeriodService periodService;
    private final CreditMemoGLConfig glConfig;

    /**
     * Create a Credit Memo to reverse invoice charges.
     * 
     * Phase 2.1 implementation with full integrations:
     * - Invoice validation via InvoiceServiceClient
     * - GL posting via GLPostingService
     * - Prior period adjustment logic via AccountingPeriodService
     * - Invoice balance update via InvoiceServiceClient
     * 
     * @param request     Credit Memo creation request
     * @param currentUser User creating the Credit Memo
     * @return Created Credit Memo details
     * @throws ResponseStatusException 404 if invoice not found, 409 if business
     *                                 rules violated
     */
    @Override
    public CreditMemoResponse createCreditMemo(
            @NonNull CreateCreditMemoRequest request,
            @NonNull String currentUser) {

        log.info("Creating Credit Memo for invoice {} with amount {}, reason: {}",
                request.getOriginalInvoiceId(), request.getCreditAmount(), request.getReasonCode());

        InvoiceDetails invoice = fetchInvoiceDetails(request.getOriginalInvoiceId());
        validateInvoiceStatus(invoice);

        BigDecimal balanceDue = resolveBalanceDue(invoice);
        validateBalanceDue(invoice, balanceDue);

        CreditAmountCalculation creditCalculation = calculateCreditAmounts(request, balanceDue);
        validateCreditDoesNotExceedBalance(request, balanceDue, creditCalculation);

        PriorPeriodInfo priorPeriodInfo = determinePriorPeriodInfo(invoice);

        CreditMemo creditMemo = creditMemoRepository.save(buildCreditMemo(
                request,
                currentUser,
                invoice,
                creditCalculation.taxReversed(),
                priorPeriodInfo));

        log.info("Created Credit Memo {} with total amount {}",
                creditMemo.getCreditMemoId(), creditMemo.calculateTotalAmount());

        postGlEntries(creditMemo, request, creditCalculation.taxReversed(), priorPeriodInfo);
        ApplyCreditMemoResponse invoiceResponse = applyCreditMemoToInvoice(creditMemo, invoice, currentUser);

        log.info("Applied Credit Memo {} to invoice {}: balance {} -> {}",
                creditMemo.getCreditMemoId(),
                invoice.getInvoiceId(),
                invoiceResponse.getBalanceBefore(),
                invoiceResponse.getBalanceAfter());

        // Build and return response
        return buildResponse(creditMemo, invoiceResponse.getBalanceAfter());
    }

    private InvoiceDetails fetchInvoiceDetails(UUID invoiceId) {
        try {
            return invoiceServiceClient.getInvoiceDetails(invoiceId);
        } catch (InvoiceServiceException e) {
            log.error("Failed to fetch invoice details for {}: {}", invoiceId, e.getMessage());
            HttpStatus status = HttpStatus.resolve(e.getHttpStatus());
            if (status == null) {
                status = HttpStatus.INTERNAL_SERVER_ERROR;
            }
            throw new ResponseStatusException(status, "Invoice not found or unavailable: " + e.getMessage());
        }
    }

    private void validateInvoiceStatus(InvoiceDetails invoice) {
        if (InvoiceStatus.VOIDED.equals(invoice.getStatus()) || InvoiceStatus.CANCELLED.equals(invoice.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Credit memos cannot be issued against " + invoice.getStatus() + " invoices");
        }
    }

    private BigDecimal resolveBalanceDue(InvoiceDetails invoice) {
        if (invoice.getBalanceDue().compareTo(BigDecimal.ZERO) != 0) {
            return invoice.getBalanceDue();
        }
        BigDecimal totalPaid = invoice.getTotalPaid() != null ? invoice.getTotalPaid() : BigDecimal.ZERO;
        return invoice.getTotalAmount().subtract(totalPaid);
    }

    private void validateBalanceDue(InvoiceDetails invoice, BigDecimal balanceDue) {
        if (balanceDue.compareTo(BigDecimal.ZERO) > 0) {
            return;
        }
        BigDecimal totalPaid = invoice.getTotalPaid() != null ? invoice.getTotalPaid() : BigDecimal.ZERO;
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Invoice has no remaining balance to credit. Balance due: " + balanceDue
                        + ", Total amount: " + invoice.getTotalAmount()
                        + ", Total paid: " + totalPaid);
    }

    private CreditAmountCalculation calculateCreditAmounts(CreateCreditMemoRequest request, BigDecimal balanceDue) {
        BigDecimal taxAmount = balanceDue.multiply(new BigDecimal("0.10"));
        BigDecimal creditRatio = request.getCreditAmount().divide(balanceDue, 4, RoundingMode.HALF_UP);
        BigDecimal taxReversed = taxAmount.multiply(creditRatio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalCreditAmount = request.getCreditAmount().add(taxReversed);
        return new CreditAmountCalculation(taxReversed, totalCreditAmount);
    }

    private void validateCreditDoesNotExceedBalance(
            CreateCreditMemoRequest request,
            BigDecimal balanceDue,
            CreditAmountCalculation creditCalculation) {
        if (creditCalculation.totalCreditAmount().compareTo(balanceDue) <= 0) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Total credit amount " + creditCalculation.totalCreditAmount()
                        + " (credit: " + request.getCreditAmount() + " + tax: " + creditCalculation.taxReversed()
                        + ")"
                        + " exceeds invoice outstanding balance " + balanceDue);
    }

    private PriorPeriodInfo determinePriorPeriodInfo(InvoiceDetails invoice) {
        if (invoice.getInvoiceDate() == null) {
            return new PriorPeriodInfo(false, null);
        }
        boolean isPriorPeriod = periodService.isPriorPeriod(invoice.getInvoiceDate());
        if (!isPriorPeriod) {
            return new PriorPeriodInfo(false, null);
        }
        String originalPeriodId = periodService.getPeriodIdForDate(invoice.getInvoiceDate());
        log.info("Credit Memo is a prior period adjustment: invoice period {}, current period {}",
                originalPeriodId, periodService.getCurrentPeriodId());
        return new PriorPeriodInfo(true, originalPeriodId);
    }

    private CreditMemo buildCreditMemo(
            CreateCreditMemoRequest request,
            String currentUser,
            InvoiceDetails invoice,
            BigDecimal taxReversed,
            PriorPeriodInfo priorPeriodInfo) {
        CreditMemo creditMemo = new CreditMemo();
        creditMemo.setOriginalInvoiceId(request.getOriginalInvoiceId());
        creditMemo.setCustomerId(invoice.getCustomerId());
        creditMemo.setCreditAmount(request.getCreditAmount());
        creditMemo.setTaxAmountReversed(taxReversed);
        creditMemo.setReasonCode(request.getReasonCode());
        creditMemo.setJustificationNote(request.getJustificationNote());
        creditMemo.setStatus(CreditMemoStatus.POSTED);
        creditMemo.setCreatedByUserId(currentUser);
        creditMemo.setPriorPeriodAdjustment(priorPeriodInfo.priorPeriod());
        creditMemo.setOriginalPeriodId(priorPeriodInfo.originalPeriodId());
        creditMemo.setCurrency(invoice.getCurrency());
        creditMemo.setPostedTimestamp(Instant.now(clock));
        return creditMemo;
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
            log.error("Failed to post GL entries for Credit Memo {}: {}",
                    creditMemo.getCreditMemoId(), e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to post GL entries: " + e.getMessage());
        }
    }

    private ApplyCreditMemoResponse applyCreditMemoToInvoice(
            CreditMemo creditMemo,
            InvoiceDetails invoice,
            String currentUser) {
        try {
            return invoiceServiceClient.applyCreditMemo(
                    invoice.getInvoiceId(),
                    ApplyCreditMemoRequest.builder()
                            .creditMemoId(creditMemo.getCreditMemoId())
                            .totalAmount(creditMemo.calculateTotalAmount())
                            .appliedBy(currentUser)
                            .currency(creditMemo.getCurrency())
                            .build());
        } catch (InvoiceServiceException e) {
            log.error("Failed to apply Credit Memo {} to invoice {}: {}",
                    creditMemo.getCreditMemoId(), invoice.getInvoiceId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Credit Memo created but failed to update invoice: " + e.getMessage());
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
            UUID customerId,
            UUID originalInvoiceId,
            CreditMemoStatus status,
            @NonNull Pageable pageable) {

        log.debug("Listing Credit Memos: customerId={}, invoiceId={}, status={}",
                customerId, originalInvoiceId, status);

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

        CreditMemo creditMemo = creditMemoRepository.findById(creditMemoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Credit Memo not found: " + creditMemoId));

        // Fetch current invoice balance from InvoiceServiceClient
        BigDecimal invoiceBalanceAfter = BigDecimal.ZERO;
        try {
            InvoiceDetails invoice = invoiceServiceClient.getInvoiceDetails(creditMemo.getOriginalInvoiceId());
            invoiceBalanceAfter = invoice.getBalanceDue();
        } catch (InvoiceServiceException e) {
            log.warn("Failed to fetch invoice balance for Credit Memo {}: {}",
                    creditMemoId, e.getMessage());
            // Balance unavailable but don't fail the request
        }

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

    public record CreditAmountCalculation(BigDecimal taxReversed, BigDecimal totalCreditAmount) {
    }

    public record PriorPeriodInfo(boolean priorPeriod, String originalPeriodId) {
    }

}
