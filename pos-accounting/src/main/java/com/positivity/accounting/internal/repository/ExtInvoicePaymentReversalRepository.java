package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ExtInvoicePaymentReversal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link ExtInvoicePaymentReversal} persistence operations — pos-accounting's
 * read-only replica of pos-invoice's completed-refund reversals (issue #1620).
 */
public interface ExtInvoicePaymentReversalRepository extends JpaRepository<ExtInvoicePaymentReversal, UUID> {

    /**
     * Sum of completed-refund amounts whose {@code reversedAt} falls in the inclusive instant
     * range. Used by collections analytics (issue #1620) to compute the {@code refunded} figure
     * on a MOVEMENT basis: a refund reduces the window it was recorded in, never the window the
     * original payment landed in, so a closed period is never restated.
     *
     * <p>Only REFUND reversals are ever stored here (see {@code
     * SettlementEventsListener#onPaymentReversed}), so this sum needs no {@code reversalType}
     * filter.
     *
     * @param start inclusive lower bound
     * @param end inclusive upper bound
     * @return total refunded amount recorded within the range; zero when there are none
     */
    @NonNull
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM ExtInvoicePaymentReversal r"
            + " WHERE r.reversedAt BETWEEN :start AND :end")
    BigDecimal sumAmountByReversedAtBetween(@Param("start") @NonNull Instant start, @Param("end") @NonNull Instant end);
}
