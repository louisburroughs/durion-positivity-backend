package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.CreateCreditMemoRequest;
import com.positivity.accounting.internal.dto.CreditMemoResponse;
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
 * Phase 2 Notes:
 * - Invoice service integration stubbed (TODO: implement InvoiceServiceClient)
 * - GL posting stubbed (TODO: implement GLPostingService integration)
 * - Accounting period logic simplified (assumes all periods open)
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
    // TODO Phase 2.1: Inject InvoiceServiceClient when available
    // private final InvoiceServiceClient invoiceServiceClient;
    // TODO Phase 2.1: Inject GLPostingService when available
    // private final GLPostingService glPostingService;
    // TODO Phase 2.1: Inject AccountingPeriodService when available
    // private final AccountingPeriodService periodService;

    /**
     * Create a Credit Memo to reverse invoice charges.
     * 
     * Phase 2 implementation with stubbed integrations:
     * - Invoice validation stubbed (assumes invoice exists and is finalized)
     * - GL posting stubbed (logs entries instead of posting)
     * - Prior period adjustment flag always false (period service unavailable)
     * - Invoice balance update stubbed (logs update instead of calling service)
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

        // TODO Phase 2.1: Replace with actual invoice service call
        // InvoiceDetails invoice = invoiceServiceClient.getInvoiceDetails(
        // request.getOriginalInvoiceId()
        // );
        //
        // if (invoice.getStatus() != InvoiceStatus.FINALIZED) {
        // throw new ResponseStatusException(HttpStatus.CONFLICT,
        // "Credit memos can only be issued against finalized invoices");
        // }
        //
        // if (request.getCreditAmount().compareTo(invoice.getOutstandingBalance()) > 0)
        // {
        // throw new ResponseStatusException(HttpStatus.CONFLICT,
        // "Credit amount cannot exceed invoice outstanding balance");
        // }

        // STUB: Mock invoice data for Phase 2
        UUID mockCustomerId = UUID.randomUUID();
        BigDecimal mockSubtotal = new BigDecimal("100.00");
        BigDecimal mockTaxAmount = new BigDecimal("10.00");
        BigDecimal mockOutstandingBalance = new BigDecimal("110.00");
        String mockCurrency = "USD";

        // Validate credit amount doesn't exceed balance (using stubbed balance)
        if (request.getCreditAmount().compareTo(mockOutstandingBalance) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Credit amount " + request.getCreditAmount()
                            + " exceeds invoice outstanding balance " + mockOutstandingBalance);
        }

        // Calculate proportional tax reversal
        BigDecimal creditRatio = request.getCreditAmount()
                .divide(mockSubtotal, 4, RoundingMode.HALF_UP);
        BigDecimal taxReversed = mockTaxAmount
                .multiply(creditRatio)
                .setScale(2, RoundingMode.HALF_UP);

        // TODO Phase 2.1: Implement prior period adjustment logic
        // AccountingPeriod invoicePeriod =
        // periodService.findByDate(invoice.getInvoiceDate());
        // AccountingPeriod currentPeriod = periodService.findCurrentOpenPeriod();
        // boolean isPriorPeriod = !invoicePeriod.getId().equals(currentPeriod.getId());

        // STUB: Assume all periods are open (no prior period adjustments)
        boolean isPriorPeriod = false;
        String originalPeriodId = null;

        // Create Credit Memo entity
        CreditMemo creditMemo = new CreditMemo();
        creditMemo.setOriginalInvoiceId(request.getOriginalInvoiceId());
        creditMemo.setCustomerId(mockCustomerId);
        creditMemo.setCreditAmount(request.getCreditAmount());
        creditMemo.setTaxAmountReversed(taxReversed);
        creditMemo.setTotalAmount(request.getCreditAmount().add(taxReversed));
        creditMemo.setReasonCode(request.getReasonCode());
        creditMemo.setJustificationNote(request.getJustificationNote());
        creditMemo.setStatus(CreditMemoStatus.POSTED);
        creditMemo.setCreatedByUserId(currentUser);
        creditMemo.setPriorPeriodAdjustment(isPriorPeriod);
        creditMemo.setOriginalPeriodId(originalPeriodId);
        creditMemo.setCurrency(mockCurrency);
        creditMemo.setPostedTimestamp(Instant.now());

        creditMemo = creditMemoRepository.save(creditMemo);

        log.info("Created Credit Memo {} with total amount {}",
                creditMemo.getCreditMemoId(), creditMemo.getTotalAmount());

        // TODO Phase 2.1: Replace with actual GL posting
        // List<GLEntry> glEntries = List.of(
        // GLEntry.debit(config.getRevenueAccountId(), request.getCreditAmount(),
        // "Revenue Reversal - CM#" + creditMemo.getCreditMemoId()),
        // GLEntry.debit(config.getSalesTaxPayableAccountId(), taxReversed,
        // "Tax Reversal - CM#" + creditMemo.getCreditMemoId()),
        // GLEntry.credit(config.getAccountsReceivableAccountId(),
        // creditMemo.getTotalAmount(),
        // "AR Reduction - CM#" + creditMemo.getCreditMemoId())
        // );
        //
        // glPostingService.postEntries(glEntries, currentPeriod.getId(),
        // isPriorPeriod, invoicePeriod.getId());

        // STUB: Log GL entries that would be posted
        log.info("STUB GL POSTING - Credit Memo {}: Debit Revenue {}, Debit Tax {}, Credit AR {}",
                creditMemo.getCreditMemoId(),
                request.getCreditAmount(),
                taxReversed,
                creditMemo.getTotalAmount());

        // TODO Phase 2.1: Replace with actual invoice service call
        // invoiceServiceClient.applyCreditMemo(
        // invoice.getInvoiceId(),
        // ApplyCreditMemoRequest.builder()
        // .creditMemoId(creditMemo.getCreditMemoId())
        // .totalAmount(creditMemo.getTotalAmount())
        // .appliedBy(currentUser)
        // .build()
        // );

        // STUB: Log invoice balance update
        BigDecimal newBalance = mockOutstandingBalance.subtract(creditMemo.getTotalAmount());
        log.info("STUB INVOICE UPDATE - Invoice {}: Balance {} -> {}",
                request.getOriginalInvoiceId(),
                mockOutstandingBalance,
                newBalance);

        // Build and return response
        return buildResponse(creditMemo, newBalance);
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

        // TODO Phase 2.1: Fetch current invoice balance from InvoiceServiceClient
        BigDecimal invoiceBalanceAfter = BigDecimal.ZERO; // Unavailable without invoice service

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
