package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.AccountingStatusChangedEvent;
import com.positivity.accounting.internal.dto.AccountingStatusView;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Public service API for reconciling POS invoice status with the authoritative
 * accounting status.
 *
 * <p>
 * Implements the observer side of the accounting status sync flow:
 * receives {@link AccountingStatusChangedEvent} events and maintains
 * a current {@link AccountingStatusView} per invoice for consumption by
 * controllers and downstream services.
 * </p>
 *
 * <p>
 * Callers of {@link #getInvoiceAccountingStatus(UUID)} require the
 * {@code VIEW_ACCOUNTING_STATUS} authority.
 * </p>
 *
 * Issue: CAP-251 #5
 */
public interface AccountingStatusSyncService {

    /**
     * Processes an accounting status change event from the accounting subsystem.
     *
     * <p>
     * Updates the local
     * {@link com.positivity.accounting.internal.entity.InvoiceStatusView}
     * with the new accounting status, timestamp, and posting reference.
     * Enforces idempotency: events with a previously seen {@code eventId} are
     * ignored.
     *
     * <p>
     * Backward status transitions (terminal → early state) are silently ignored
     * with a warning log; they do NOT throw.
     * </p>
     *
     * @param event the accounting status changed event; must not be {@code null}
     */
    void processStatusEvent(@NonNull AccountingStatusChangedEvent event);

    /**
     * Returns the current accounting status view for the given invoice.
     *
     * <p>
     * Requires {@code VIEW_ACCOUNTING_STATUS} authority on the calling principal.
     * Sets {@link AccountingStatusView#isStale()} to {@code true} when
     * {@code accountingStatusUpdatedAt} exceeds the freshness threshold.
     * </p>
     *
     * @param invoiceId the invoice identifier; must not be {@code null}
     * @return the accounting status view with staleness indicator
     * @throws org.springframework.security.access.AccessDeniedException when
     *                                                                   the caller
     *                                                                   lacks
     *                                                                   {@code VIEW_ACCOUNTING_STATUS}
     *                                                                   authority
     * @throws jakarta.persistence.EntityNotFoundException               when no
     *                                                                   invoice
     *                                                                   record
     *                                                                   exists
     */
    @PreAuthorize("hasAuthority('VIEW_ACCOUNTING_STATUS')")
    @NonNull
    AccountingStatusView getInvoiceAccountingStatus(@NonNull UUID invoiceId);

    /**
     * Returns the full accounting status detail for an invoice, including
     * discrepancy fields.
     *
     * <p>
     * Requires {@code VIEW_ACCOUNTING_DETAIL} authority.
     * </p>
     *
     * @param invoiceId the invoice identifier; must not be {@code null}
     * @return the full accounting status view including discrepancy fields
     * @throws jakarta.persistence.EntityNotFoundException when no record exists
     */
    @PreAuthorize("hasAuthority('VIEW_ACCOUNTING_DETAIL')")
    @NonNull
    AccountingStatusView getInvoiceAccountingStatusDetail(@NonNull UUID invoiceId);

    /**
     * Stub: re-reads and returns the current accounting status for an invoice.
     *
     * <p>
     * Pending event-driven refresh implementation. For now this is a read-through
     * stub that returns current persisted state.
     * </p>
     *
     * <p>
     * Requires {@code REFRESH_ACCOUNTING_STATUS} authority.
     * </p>
     *
     * @param invoiceId the invoice identifier; must not be {@code null}
     * @return the current accounting status view
     * @throws jakarta.persistence.EntityNotFoundException when no record exists
     */
    @PreAuthorize("hasAuthority('REFRESH_ACCOUNTING_STATUS')")
    @NonNull
    AccountingStatusView refreshAccountingStatus(@NonNull UUID invoiceId);
}
