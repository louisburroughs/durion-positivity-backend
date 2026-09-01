package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.PaymentApplicationListRow;
import java.time.LocalDate;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Read-only listing of pos-accounting's own A/R cash applications by applied-date window (Wave 2
 * E10, issue #1598).
 *
 * <p><b>Scope note (issue #1605):</b> this service is scoped strictly to pos-accounting's own
 * {@code PaymentApplication} records (cash applications of a {@code ReceivablePayment} to an
 * invoice). It does NOT reflect {@code pos-invoice}'s {@code DepositCreditApplication}
 * (deposit-credit draw-downs settling an invoice) or {@code RefundRecord} — whether this endpoint
 * should also surface those is an open cross-module architecture question tracked on issue #1605
 * and is deliberately NOT decided here. This module does not read pos-invoice's entities or
 * database (ADR-0026/ADR-0044).
 */
public interface PaymentApplicationQueryService {

    /** Maximum allowed {@code appliedTo - appliedFrom} span, in days. */
    int MAX_APPLIED_DATE_WINDOW_DAYS = 366;

    /**
     * List payment applications whose {@code applicationTimestamp} falls in {@code [appliedFrom,
     * appliedTo]}, ordered by {@code appliedAt} ascending.
     *
     * <p>By default (({@code includeReversed=false}) applications that have since been reversed
     * (a {@code PaymentApplicationReversal} exists) are EXCLUDED entirely from the list — not
     * merely flagged. Pass {@code includeReversed=true} to include them; every row's {@code
     * reversed} flag then reports whether that specific application was reversed.
     *
     * <p>The window is effectively required and bounded: {@code appliedTo - appliedFrom} may not
     * exceed {@link #MAX_APPLIED_DATE_WINDOW_DAYS} days, to keep this a bounded-scan query.
     *
     * @param appliedFrom     window start (inclusive)
     * @param appliedTo       window end (inclusive)
     * @param includeReversed when {@code true}, includes reversed applications (flagged via
     *                        {@code reversed=true} on their row) instead of excluding them
     * @param pageable        page number/size (size capped server-side; sort is server-controlled)
     * @return page of matching applications, appliedAt ascending
     * @throws IllegalArgumentException if {@code appliedTo} is before {@code appliedFrom}, or the
     *                                  window exceeds {@link #MAX_APPLIED_DATE_WINDOW_DAYS} days
     */
    @NonNull
    Page<PaymentApplicationListRow> listByAppliedDateWindow(
            @NonNull LocalDate appliedFrom,
            @NonNull LocalDate appliedTo,
            boolean includeReversed,
            @NonNull Pageable pageable);
}
