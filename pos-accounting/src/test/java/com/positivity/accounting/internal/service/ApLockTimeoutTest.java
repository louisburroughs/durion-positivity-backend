package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.DatabaseDialectSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code SET LOCAL lock_timeout} on the AP command paths (CAP:550 S42, #2627). */
@DisplayName("AP lock timeout: SET LOCAL lock_timeout from accounting.ap.lock-timeout (#2627)")
class ApLockTimeoutTest {

    private final EntityManager entityManager = mock(EntityManager.class);
    private final DatabaseDialectSupport dialect = mock(DatabaseDialectSupport.class);

    @Test
    @DisplayName("on PostgreSQL: the transaction-local setting, its value bound as a parameter in milliseconds")
    void setsTheTransactionLocalTimeout() {
        Query query = mock(Query.class);
        when(dialect.isPostgreSql()).thenReturn(true);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter("timeout", "5000ms")).thenReturn(query);

        new ApLockTimeout(entityManager, dialect, Duration.ofSeconds(5)).apply();

        verify(entityManager).createNativeQuery("SELECT set_config('lock_timeout', :timeout, true)");
        verify(query).setParameter("timeout", "5000ms");
        verify(query).getSingleResult();
    }

    @Test
    @DisplayName("on H2 nothing is run")
    void nothingOnH2() {
        new ApLockTimeout(entityManager, dialect, Duration.ofSeconds(5)).apply();

        verifyNoInteractions(entityManager);
    }

    @Test
    @DisplayName("a zero or negative timeout is refused at startup")
    void positiveTimeoutOnly() {
        assertThatThrownBy(() -> new ApLockTimeout(entityManager, dialect, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accounting.ap.lock-timeout");
        assertThat(new ApLockTimeout(entityManager, dialect, Duration.ofMillis(1500)).timeout())
                .isEqualTo(Duration.ofMillis(1500));
    }
}
