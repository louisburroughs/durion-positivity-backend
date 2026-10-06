package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serializes the transactions that record a payment or a refund of it, per {@code paymentIntentId}
 * (#2556): the settlement handler, the refund handler and the {@code INVOICE_PAYMENT} processor each take
 * this lock first in their transaction. Without it, a refund and the fact that records its payment can
 * commit at the same time, each reading before the other's write: the refund finds no payment and the
 * recording finds no refund, so the refund is never released.
 *
 * <p>A Postgres transaction-scoped advisory lock ({@code pg_advisory_xact_lock}) on the transaction's own
 * connection: no row, no second pooled connection, released at commit or rollback. On any other database
 * (the H2 {@code test} profile) it does nothing; those tests run one thread.
 */
@Slf4j
@Component
public class PaymentIntentLock {

    private final ReceivablePaymentRepository receivablePaymentRepository;
    private final boolean postgres;

    public PaymentIntentLock(
            ReceivablePaymentRepository receivablePaymentRepository, EntityManagerFactory entityManagerFactory) {
        this.receivablePaymentRepository = receivablePaymentRepository;
        this.postgres = entityManagerFactory
                        .unwrap(SessionFactoryImplementor.class)
                        .getJdbcServices()
                        .getDialect()
                instanceof PostgreSQLDialect;
        if (!postgres) {
            log.info("Payment intent lock disabled: the database is not Postgres");
        }
    }

    /**
     * Wait for, then hold until this transaction ends, the lock of {@code paymentIntentId}.
     *
     * @param paymentIntentId the payment
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(@NonNull UUID paymentIntentId) {
        if (postgres) {
            receivablePaymentRepository.takeAdvisoryTransactionLock(key(paymentIntentId));
        }
    }

    /** The lock key of a payment: both halves of its id folded into one stable 64-bit value. */
    static long key(@NonNull UUID paymentIntentId) {
        return paymentIntentId.getMostSignificantBits() ^ paymentIntentId.getLeastSignificantBits();
    }
}
