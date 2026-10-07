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
 * <li>GoodsReceivedEvent creates bill in PENDING_RECEIPT_MATCH status; nothing posts (AW37)</li>
 * <li>VendorInvoiceReceivedEvent triggers three-way match validation</li>
 * <li>A HIGH match goes to AWAITING_APPROVAL (submitted by SYSTEM); it never approves (#2509, G12)</li>
 * <li>A MEDIUM match or a discrepancy goes to MATCH_EXCEPTION for a person to resolve</li>
 * </ol>
 * The decisions themselves (submit, approve, reject, resolve, select, void) are {@link VendorBillApprovalService}'s.
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
     * <li>Initial status: PENDING_RECEIPT_MATCH</li>
     * <li>No GL posting: the bill posts at approval (AW37)</li>
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
     * <li>Scores the vendor's pending bills (P3) and picks the best</li>
     * <li>Validates quantities (±0.1% tolerance) and prices (±5% tolerance)</li>
     * <li>HIGH match: AWAITING_APPROVAL, submitted by SYSTEM; never approved here (#2509)</li>
     * <li>MEDIUM match or discrepancy: MATCH_EXCEPTION</li>
     * <li>Every routed match keeps the billed lines and total and writes its evidence (AW39)</li>
     * </ul>
     *
     * @param event VendorInvoiceReceivedEvent from vendor/AP system
     * @return VendorBillResponse with updated bill status
     * @throws IllegalArgumentException if bill not found or already matched
     */
    @NonNull
    VendorBillResponse handleVendorInvoiceReceivedEvent(@NonNull VendorInvoiceReceivedEvent event);

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
