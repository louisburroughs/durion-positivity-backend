package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.entity.UndepositedSession;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.enums.UndepositedSessionStatus;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import com.positivity.accounting.internal.repository.UndepositedSessionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sources of the cash position's aged "Needs attention" items (CAP:550 S19, #2515; SPEC-accounting-workspace §4.5,
 * §4.6): undeposited register sessions and vendor cash-on-delivery payments left unapplied. The work-items read asks
 * the same questions, so both answer them here. "Older than N days" means more than N calendar days before today on the
 * tenant accounting date, so a session or payment exactly N days old is not yet aged.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CashAttentionSources {

    /** AP payments whose unapplied amount is not owed to the vendor any more: voided and returned ones. */
    static final Set<APPaymentStatus> UNCOUNTED_PAYMENT_STATUSES =
            Set.of(APPaymentStatus.VOIDED, APPaymentStatus.RETURNED);

    private final UndepositedSessionRepository sessions;
    private final APPaymentRepository apPayments;
    private final AccountingCalendarZoneResolver zoneResolver;
    private final LedgerCurrency ledgerCurrency;

    /**
     * An undeposited session with its business date (its close date in the tenant accounting time zone) and whether its
     * deposit balances: the bank drops equal the expected cash plus the clearing net.
     */
    public record AgedSession(
            @NonNull UndepositedSession session, @NonNull LocalDate businessDate, boolean balanced) {

        /** The money the session's bank drops would put in the bank. */
        public @NonNull BigDecimal depositAmount() {
            return session.getDepositAmount();
        }
    }

    /** Every session waiting to be deposited, oldest close first; card-only sessions (nothing to deposit) never wait. */
    public @NonNull List<AgedSession> undepositedSessions() {
        return sessions.findByStatusOrderByClosedAtAscSessionIdAsc(UndepositedSessionStatus.UNDEPOSITED).stream()
                .map(session -> new AgedSession(
                        session,
                        zoneResolver.postingDate(session.getClosedAt()),
                        session.getDepositAmount()
                                        .subtract(session.getExpectedCash())
                                        .subtract(session.getClearingNet())
                                        .signum()
                                == 0))
                .toList();
    }

    /** The undeposited sessions older than {@code agedDays} days on {@code today}, oldest first. */
    public @NonNull List<AgedSession> agedUndepositedSessions(@NonNull LocalDate today, int agedDays) {
        return aged(undepositedSessions(), today, agedDays);
    }

    /** {@code sessions} narrowed to those older than {@code agedDays} days on {@code today}. */
    public static @NonNull List<AgedSession> aged(
            @NonNull List<AgedSession> sessions, @NonNull LocalDate today, int agedDays) {
        LocalDate cutoff = today.minusDays(agedDays);
        return sessions.stream()
                .filter(session -> session.businessDate().isBefore(cutoff))
                .toList();
    }

    /**
     * The vendor cash-on-delivery payments, not voided or returned, in the ledger currency, that still hold unapplied
     * money and are dated more than {@code agedDays} days before {@code today}, oldest first.
     */
    public @NonNull List<APPayment> agedUnappliedVendorCod(@NonNull LocalDate today, int agedDays) {
        return apPayments.findUnappliedByMethodBefore(
                PaymentMethod.CASH, ledgerCurrency.code(), UNCOUNTED_PAYMENT_STATUSES, today.minusDays(agedDays));
    }
}
