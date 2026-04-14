package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.CreateCreditMemoRequest;
import com.positivity.accounting.internal.dto.CreditMemoResponse;
import com.positivity.accounting.internal.enums.CreditMemoStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

public interface CreditMemoService {

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
    CreditMemoResponse createCreditMemo(CreateCreditMemoRequest request, String currentUser);

    /**
     * List Credit Memos with optional filters.
     *
     * @param customerId        Optional customer filter
     * @param originalInvoiceId Optional invoice filter
     * @param status            Optional status filter
     * @param pageable          Pagination parameters
     * @return Paginated Credit Memo list
     */
    Page<CreditMemoResponse> listCreditMemos(
            UUID customerId, UUID originalInvoiceId, CreditMemoStatus status, Pageable pageable);

    /**
     * Get a Credit Memo by ID.
     *
     * @param creditMemoId Credit Memo identifier
     * @return Credit Memo details
     * @throws ResponseStatusException 404 if Credit Memo not found
     */
    CreditMemoResponse getCreditMemo(UUID creditMemoId);
}
