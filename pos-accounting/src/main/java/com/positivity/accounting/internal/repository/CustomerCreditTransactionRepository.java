package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.CustomerCreditTransaction;
import com.positivity.accounting.internal.enums.CustomerCreditTransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for customer-credit draw-downs (issue #992).
 */
public interface CustomerCreditTransactionRepository extends JpaRepository<CustomerCreditTransaction, UUID> {

    /**
     * Look up a draw-down by the caller's idempotency key. A hit means the request was
     * already processed and must not relieve the liability again.
     *
     * @param requestId caller-supplied idempotency key
     * @return the existing transaction, if any
     */
    Optional<CustomerCreditTransaction> findByRequestId(String requestId);

    /**
     * All draw-downs of a credit, newest first.
     *
     * @param creditId credit identifier
     * @return the credit's transaction history
     */
    List<CustomerCreditTransaction> findByCreditIdOrderByCreatedAtDesc(UUID creditId);

    /**
     * Credit applied against an invoice. Feeds the invoice balance derivation so an
     * invoice settled by a customer credit is no longer shown as outstanding.
     *
     * @param invoiceId invoice identifier
     * @param type      draw-down type (always {@code APPLICATION} for balance purposes)
     * @return total credit applied to that invoice (zero when none)
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM CustomerCreditTransaction t"
            + " WHERE t.invoiceId = :invoiceId AND t.transactionType = :type")
    BigDecimal sumAmountByInvoiceIdAndType(UUID invoiceId, CustomerCreditTransactionType type);

    /**
     * Sum of draw-down amounts of the given type whose {@code createdAt} falls in the inclusive
     * instant range. Used with {@link CustomerCreditTransactionType#APPLICATION} by collections
     * analytics (issue #1621) to fold this subledger's own no-new-cash settlement into {@code
     * nonCashSettled}, alongside the pos-invoice deposit-credit replica.
     *
     * <p>{@code createdAt} is the draw-down moment: this entity carries no separate business
     * timestamp, so unlike {@link
     * com.positivity.accounting.internal.repository.ExtInvoiceDepositCreditApplicationRepository#sumAmountAppliedByAppliedAtBetween}
     * there is no {@code appliedAt} to prefer.
     *
     * @param type  draw-down type to sum (pass {@code APPLICATION} for the collections figure)
     * @param start inclusive lower bound
     * @param end   inclusive upper bound
     * @return total drawn-down amount of that type within the range; zero when there are none
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM CustomerCreditTransaction t"
            + " WHERE t.transactionType = :type AND t.createdAt BETWEEN :start AND :end")
    BigDecimal sumAmountByTypeAndCreatedAtBetween(
            @Param("type") CustomerCreditTransactionType type,
            @Param("start") Instant start,
            @Param("end") Instant end);
}
