package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

@DisplayName("PaymentIntentLock (#2556)")
class PaymentIntentLockTest {

    private static final UUID INTENT = UUID.fromString("0199a000-0000-7000-8000-000000002556");

    private final ReceivablePaymentRepository repository = mock(ReceivablePaymentRepository.class);

    private PaymentIntentLock lockOn(Dialect dialect) {
        EntityManagerFactory factory = mock(EntityManagerFactory.class);
        SessionFactoryImplementor sessionFactory = mock(SessionFactoryImplementor.class, Answers.RETURNS_DEEP_STUBS);
        when(factory.unwrap(SessionFactoryImplementor.class)).thenReturn(sessionFactory);
        when(sessionFactory.getJdbcServices().getDialect()).thenReturn(dialect);
        return new PaymentIntentLock(repository, factory);
    }

    @Test
    @DisplayName("on Postgres it takes the advisory transaction lock keyed on the payment")
    void postgresTakesTheAdvisoryLock() {
        lockOn(mock(PostgreSQLDialect.class)).lock(INTENT);

        verify(repository).takeAdvisoryTransactionLock(PaymentIntentLock.key(INTENT));
    }

    @Test
    @DisplayName("on any other database (the H2 test profile) it does nothing")
    void otherDatabasesSkipIt() {
        lockOn(mock(H2Dialect.class)).lock(INTENT);

        verify(repository, never()).takeAdvisoryTransactionLock(anyLong());
    }

    @Test
    @DisplayName("the key is stable per payment, so every path locks the same key, and differs between payments")
    void keyIsStablePerPayment() {
        assertThat(PaymentIntentLock.key(INTENT))
                .isEqualTo(PaymentIntentLock.key(UUID.fromString(INTENT.toString())))
                .isEqualTo(INTENT.getMostSignificantBits() ^ INTENT.getLeastSignificantBits())
                .isNotEqualTo(PaymentIntentLock.key(UUID.fromString("0199a000-0000-7000-8000-000000002557")));
    }
}
