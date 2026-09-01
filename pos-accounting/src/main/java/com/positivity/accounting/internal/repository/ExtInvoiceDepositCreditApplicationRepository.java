package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ExtInvoiceDepositCreditApplication;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtInvoiceDepositCreditApplicationRepository
        extends JpaRepository<ExtInvoiceDepositCreditApplication, UUID> {

    /**
     * Sum of deposit-credit draw-down amounts whose {@code appliedAt} falls in the inclusive
     * instant range. Used by collections analytics (issue #1621) to compute the {@code
     * nonCashSettled} figure on a MOVEMENT basis, mirroring {@link
     * ExtInvoicePaymentReversalRepository#sumAmountByReversedAtBetween}.
     *
     * @param start inclusive lower bound
     * @param end inclusive upper bound
     * @return total amount applied within the range; zero when there are none
     */
    @Query("SELECT COALESCE(SUM(a.amountApplied), 0) FROM ExtInvoiceDepositCreditApplication a"
            + " WHERE a.appliedAt BETWEEN :start AND :end")
    BigDecimal sumAmountAppliedByAppliedAtBetween(@Param("start") Instant start, @Param("end") Instant end);

    /**
     * Whether the given deposit credit has already been applied to the given invoice.
     * pos-invoice's {@code applyAvailableCredits()} applies a given credit to a given invoice at
     * most once, so this doubles as the listener's duplicate guard when a replay event carries a
     * fresh eventId for the same fact (issue #1621).
     *
     * @param depositCreditId deposit credit identifier
     * @param invoiceId invoice identifier
     * @return true if an application row already exists for this pair
     */
    boolean existsByDepositCreditIdAndInvoiceId(UUID depositCreditId, UUID invoiceId);
}
