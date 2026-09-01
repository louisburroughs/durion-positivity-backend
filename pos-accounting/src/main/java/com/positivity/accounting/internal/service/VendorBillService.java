package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.dto.VendorBillListRow;
import com.positivity.accounting.internal.dto.VendorBillMatchCandidateResponse;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorInvoiceReceivedEvent;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service for Vendor Bill lifecycle management (Issue #130).
 *
 * <p>
 * Receipt Accrual Workflow:
 * <ol>
 * <li>GoodsReceivedEvent creates bill in PENDING_RECEIPT_MATCH status</li>
 * <li>GL posting: Dr Inventory/Expense, Cr AP (provisional)</li>
 * <li>VendorInvoiceReceivedEvent triggers three-way match validation</li>
 * <li>If matched: bill auto-transitions to APPROVED</li>
 * <li>If discrepancy: bill transitions to MATCH_EXCEPTION for manual
 * resolution</li>
 * </ol>
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/130">Issue
 *      #130</a>
 */
public interface VendorBillService {

    /**
     * Create vendor bill from GoodsReceivedEvent (primary trigger).
     *
     * <p>
     * Business rules:
     * <ul>
     * <li>Idempotent by eventId: duplicate events ignored</li>
     * <li>Validates PO and vendor exist</li>
     * <li>Initial status: PENDING_RECEIPT_MATCH</li>
     * <li>GL posting: Dr Inventory/Expense, Cr AP</li>
     * <li>Traceability: links eventId → bill → journalEntryId</li>
     * </ul>
     *
     * @param event GoodsReceivedEvent from inventory/purchasing system
     * @return VendorBillResponse with bill details
     * @throws IllegalArgumentException if PO/vendor not found or validation fails
     */
    @NonNull
    VendorBillResponse handleGoodsReceivedEvent(@NonNull GoodsReceivedEvent event);

    /**
     * Match vendor invoice against existing bill (three-way match).
     *
     * <p>
     * Matching logic:
     * <ul>
     * <li>Finds bill by vendor + invoice reference</li>
     * <li>Validates quantities (±0.1% tolerance) and prices (±5% tolerance)</li>
     * <li>If matched: bill auto-transitions to APPROVED</li>
     * <li>If discrepancy: bill transitions to MATCH_EXCEPTION</li>
     * </ul>
     *
     * @param event VendorInvoiceReceivedEvent from vendor/AP system
     * @return VendorBillResponse with updated bill status
     * @throws IllegalArgumentException if bill not found or already matched
     */
    @NonNull
    VendorBillResponse handleVendorInvoiceReceivedEvent(@NonNull VendorInvoiceReceivedEvent event);

    /**
     * Resolve match exception (accept, correct, or void).
     *
     * @param billId           Vendor bill UUID
     * @param resolutionAction Action: ACCEPT, CORRECT, VOID
     * @param reason           Justification for resolution
     * @param operatorId       User resolving exception
     * @return Updated bill response
     */
    @NonNull
    VendorBillResponse resolveMatchException(
            @NonNull UUID billId, @NonNull String resolutionAction, @NonNull String reason, @NonNull String operatorId);

    /**
     * Get vendor bill by ID.
     *
     * @param billId Vendor bill UUID
     * @return Optional bill response
     */
    @NonNull
    Optional<VendorBillResponse> getBillById(@NonNull UUID billId);

    /**
     * Get vendor bill by origin event ID (for idempotency checks).
     *
     * @param originEventId Event ID from GoodsReceivedEvent
     * @return Optional bill response
     */
    @NonNull
    Optional<VendorBillResponse> getBillByOriginEventId(@NonNull UUID originEventId);

    /**
     * List unresolved match candidates for an ambiguous invoice match.
     *
     * <p>
     * When a vendor invoice matches multiple pending bills, candidates are
     * persisted for manual selection. This method returns all unresolved
     * candidates for a given invoice event, ordered by score descending.
     * </p>
     *
     * @param invoiceEventId the invoice event that triggered the ambiguous match
     * @return list of match candidates with scores
     */
    @NonNull
    List<VendorBillMatchCandidateResponse> listMatchCandidates(@NonNull UUID invoiceEventId);

    /**
     * Select a specific match candidate for an ambiguous invoice match.
     *
     * <p>
     * Operator picks one candidate from the list. The selected bill is
     * transitioned from MATCH_EXCEPTION and the three-way match validation
     * proceeds. All other candidates for the same invoice are marked as
     * resolved (not selected).
     * </p>
     *
     * @param candidateId the candidate record ID to select
     * @param operatorId  the operator performing the selection
     * @return the updated vendor bill response
     * @throws IllegalArgumentException if candidate not found or already resolved
     */
    @NonNull
    VendorBillResponse selectMatchCandidate(@NonNull UUID candidateId, @NonNull String operatorId);

    /**
     * List vendor bills due in a date window, optionally filtered by status (Wave 2 E9, issue
     * #1597).
     *
     * <p>The window is effectively required and bounded: to keep this a bounded-scan query
     * rather than an unbounded table scan, {@code dueTo - dueFrom} may not exceed {@link
     * #MAX_DUE_DATE_WINDOW_DAYS} days.
     *
     * <p>Results are ordered by {@code dueDate} ascending — this ordering is server-controlled
     * (any sort supplied on {@code pageable} is ignored), matching the precedent set by {@code
     * APPaymentService#listEligibleBills}.
     *
     * @param dueFrom  window start (inclusive)
     * @param dueTo    window end (inclusive)
     * @param status   optional status filter; {@code null} matches bills of any status
     * @param pageable page number and size (size is capped server-side; sort is ignored)
     * @return page of matching bills mapped to the list row shape, dueDate ascending
     * @throws IllegalArgumentException if {@code dueTo} is before {@code dueFrom}, or the window
     *                                  exceeds {@link #MAX_DUE_DATE_WINDOW_DAYS} days
     */
    @NonNull
    Page<VendorBillListRow> listByDueDateWindow(
            @NonNull LocalDate dueFrom,
            @NonNull LocalDate dueTo,
            @Nullable VendorBillStatus status,
            @NonNull Pageable pageable);

    /** Maximum allowed {@code dueTo - dueFrom} span, in days, for {@link #listByDueDateWindow}. */
    int MAX_DUE_DATE_WINDOW_DAYS = 366;
}
