package com.positivity.accounting.service;

import com.positivity.accounting.internal.client.InvoiceServiceClient;
import com.positivity.accounting.internal.client.InvoiceServiceException;
import com.positivity.accounting.internal.config.CreditMemoGLConfig;
import com.positivity.accounting.internal.dto.ApplyCreditMemoRequest;
import com.positivity.accounting.internal.dto.ApplyCreditMemoResponse;
import com.positivity.accounting.internal.dto.CreateCreditMemoRequest;
import com.positivity.accounting.internal.dto.CreditMemoResponse;
import com.positivity.accounting.internal.dto.InvoiceDetails;
import com.positivity.accounting.internal.entity.CreditMemo;
import com.positivity.accounting.internal.entity.CreditMemoStatus;
import com.positivity.accounting.internal.repository.CreditMemoRepository;
import com.positivity.security.common.SecurityContextHelper;
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
public class CreditMemoService {

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
    public CreditMemoResponse createCreditMemo(
            @NonNull CreateCreditMemoRequest request,
            @NonNull String currentUser) {

        log.info("Creating Credit Memo for invoice {} with amount {}, reason: {}",
                request.getOriginalInvoiceId(), request.getCreditAmount(), request.getReasonCode());

        // Fetch invoice details from Invoice service
        InvoiceDetails invoice;
        try {
            invoice = invoiceServiceClient.getInvoiceDetails(request.getOriginalInvoiceId());
        } catch (InvoiceServiceException e) {
            log.error("Failed to fetch invoice details for {}: {}", request.getOriginalInvoiceId(), e.getMessage());
            throw new ResponseStatusException(
                    HttpStatus.valueOf(e.getHttpStatus()),
                    "Invoice not found or unavailable: " + e.getMessage());
        }

        // Validate invoice status (must be finalized/open)
        if ("VOIDED".equalsIgnoreCase(invoice.getStatus()) || "CANCELLED".equalsIgnoreCase(invoice.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Credit memos cannot be issued against " + invoice.getStatus() + " invoices");
        }

        // Validate credit amount doesn't exceed balance
        if (request.getCreditAmount().compareTo(invoice.getBalanceDue()) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Credit amount " + request.getCreditAmount()
                            + " exceeds invoice outstanding balance " + invoice.getBalanceDue());
        }

        // Calculate proportional tax reversal
        BigDecimal subtotal = invoice.getTotalAmount().subtract(
                invoice.getTotalPaid() != null ? invoice.getTotalPaid() : BigDecimal.ZERO);
        BigDecimal taxAmount = subtotal.multiply(new BigDecimal("0.10")); // Simplified: assume 10% tax
        BigDecimal creditRatio = request.getCreditAmount()
                .divide(subtotal, 4, RoundingMode.HALF_UP);
        BigDecimal taxReversed = taxAmount
                .multiply(creditRatio)
                .setScale(2, RoundingMode.HALF_UP);

        // Check if prior period adjustment
        boolean isPriorPeriod = false;
        String originalPeriodId = null;
        if (invoice.getInvoiceDate() != null) {
            isPriorPeriod = periodService.isPriorPeriod(invoice.getInvoiceDate());
            if (isPriorPeriod) {
                originalPeriodId = periodService.getPeriodIdForDate(invoice.getInvoiceDate());
                log.info("Credit Memo is a prior period adjustment: invoice period {}, current period {}",
                        originalPeriodId, periodService.getCurrentPeriodId());
            }
        }

        // Create Credit Memo entity
        CreditMemo creditMemo = new CreditMemo();
        creditMemo.setOriginalInvoiceId(request.getOriginalInvoiceId());
        creditMemo.setCustomerId(invoice.getCustomerId());
        creditMemo.setCreditAmount(request.getCreditAmount());
        creditMemo.setTaxAmountReversed(taxReversed);
        creditMemo.setTotalAmount(request.getCreditAmount().add(taxReversed));
        creditMemo.setReasonCode(request.getReasonCode());
        creditMemo.setJustificationNote(request.getJustificationNote());
        creditMemo.setStatus(CreditMemoStatus.POSTED);
        creditMemo.setCreatedByUserId(currentUser);
        creditMemo.setPriorPeriodAdjustment(isPriorPeriod);
        creditMemo.setOriginalPeriodId(originalPeriodId);
        creditMemo.setCurrency(invoice.getCurrency());
        creditMemo.setPostedTimestamp(Instant.now());

        creditMemo = creditMemoRepository.save(creditMemo);

        log.info("Created Credit Memo {} with total amount {}",
                creditMemo.getCreditMemoId(), creditMemo.getTotalAmount());

        // Post GL entries
        try {
            glPostingService.postCreditMemoReversal(
                    creditMemo.getCreditMemoId(),
                    glConfig.getRevenueAccountId(),
                    glConfig.getTaxPayableAccountId(),
                    glConfig.getArAccountId(),
                    request.getCreditAmount(),
                    taxReversed,
                    "Credit Memo " + creditMemo.getCreditMemoId() + " - " + request.getReasonCode(),
                    isPriorPeriod,
                    originalPeriodId);
        } catch (Exception e) {
            log.error("Failed to post GL entries for Credit Memo {}: {}",
                    creditMemo.getCreditMemoId(), e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to post GL entries: " + e.getMessage());
        }

        // Apply Credit Memo to invoice
        ApplyCreditMemoResponse invoiceResponse;
        try {
            invoiceResponse = invoiceServiceClient.applyCreditMemo(
                    invoice.getInvoiceId(),
                    ApplyCreditMemoRequest.builder()
                            .creditMemoId(creditMemo.getCreditMemoId())
                            .totalAmount(creditMemo.getTotalAmount())
                            .appliedBy(currentUser)
                            .currency(creditMemo.getCurrency())
                            .build());
        } catch (InvoiceServiceException e) {
            log.error("Failed to apply Credit Memo {} to invoice {}: {}",
                    creditMemo.getCreditMemoId(), invoice.getInvoiceId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Credit Memo created but failed to update invoice: " + e.getMessage());
        }

        log.info("Applied Credit Memo {} to invoice {}: balance {} -> {}",
                creditMemo.getCreditMemoId(),
                invoice.getInvoiceId(),
                invoiceResponse.getBalanceBefore(),
                invoiceResponse.getBalanceAfter());

        // Build and return response
        return buildResponse(creditMemo, invoiceResponse.getBalanceAfter());
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
            creditMemos = creditMemoRepository.findByOriginalInvoiceId(originalInvoiceId)
                    .stream()
                    .collect(java.util.stream.Collectors.collectingAndThen(
                            java.util.stream.Collectors.toList(),
                            list -> new org.springframework.data.domain.PageImpl<>(
                                    list, pageable, list.size())));
        } else if (status != null) {
            creditMemos = creditMemoRepository.findByStatus(status, pageable);
        } else {
            creditMemos = creditMemoRepository.findAll(pageable);
        }

        return creditMemos.map(cm -> buildResponse(cm, BigDecimal.ZERO)); // Balance unavailable in list view
    }

    /**
     * Get a Credit Memo by ID.
     * 
     * @param creditMemoId Credit Memo identifier
     * @return Credit Memo details
     * @throws ResponseStatusException 404 if Credit Memo not found
     */
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
        response.setTotalAmount(creditMemo.getTotalAmount());
        response.setReasonCode(creditMemo.getReasonCode());
        response.setJustificationNote(creditMemo.getJustificationNote());
        response.setStatus(creditMemo.getStatus().name());
        response.setCreationTimestamp(creditMemo.getCreationTimestamp());
        response.setPostedTimestamp(creditMemo.getPostedTimestamp());
        response.setCreatedByUserId(creditMemo.getCreatedByUserId());
        response.setPriorPeriodAdjustment(creditMemo.getPriorPeriodAdjustment());
        response.setOriginalPeriodId(creditMemo.getOriginalPeriodId());
        response.setCurrency(creditMemo.getCurrency());
        response.setInvoiceBalanceAfter(invoiceBalanceAfter);
        return response;
    }

    /**
     * Get current user from security context.
     */
    private String getCurrentUser() {
        return SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "User not authenticated"));
    }
}
