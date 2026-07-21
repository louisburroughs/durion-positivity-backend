package com.positivity.tax.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxProviderTransactionResult;
import com.positivity.tax.common.enums.TaxProviderTransactionStatus;
import com.positivity.tax.internal.entity.TaxProviderTransaction;
import com.positivity.tax.internal.exception.TaxCalculationException;
import com.positivity.tax.internal.repository.TaxProviderTransactionRepository;
import com.positivity.tax.service.TaxProviderClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story T6 / decision D-T3: the lifecycle service records commit/void rows, is idempotent
 * on {@code referenceId}, records PENDING_COMMIT when the provider fails (never blocking the
 * sale) and the re-commit job promotes PENDING_COMMIT → COMMITTED when the provider recovers.
 *
 * <p>Exercised against H2 with the real Flyway {@code V2__tax_provider_transaction.sql}
 * migration and Hibernate schema validation.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_tax_lifecycle;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.flyway.enabled=true",
            "spring.flyway.locations=classpath:db/migration"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class TaxProviderLifecycleServiceTest {

    @Autowired
    private TaxProviderTransactionRepository repository;

    private ControllableProvider provider;
    private TaxProviderLifecycleService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        provider = new ControllableProvider();
        TaxProviderSelector selector = mock(TaxProviderSelector.class);
        when(selector.select()).thenReturn(provider);
        ObjectProvider<MeterRegistry> meterRegistry = mock(ObjectProvider.class);
        when(meterRegistry.getIfAvailable()).thenReturn(null);
        TaxProviderTransactionResolver resolver = new TaxProviderTransactionResolver(repository);
        service = new TaxProviderLifecycleService(selector, repository, resolver, meterRegistry);
    }

    @Test
    @DisplayName("commit records a COMMITTED row with the external transaction id")
    void commitRecordsCommittedRow() {
        UUID ref = UUID.randomUUID();

        TaxProviderTransactionResult result = service.commit(ref, "INVOICE");

        assertThat(result.status()).isEqualTo(TaxProviderTransactionStatus.COMMITTED);
        TaxProviderTransaction row = repository.findByReferenceId(ref).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(TaxProviderTransactionStatus.COMMITTED);
        assertThat(row.getReferenceType()).isEqualTo("INVOICE");
        assertThat(row.getProvider()).isEqualTo("FAKE");
        assertThat(row.getExternalTransactionId()).isEqualTo("ext-123");
        assertThat(row.getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("void records a VOIDED row")
    void voidRecordsVoidedRow() {
        UUID ref = UUID.randomUUID();

        TaxProviderTransactionResult result = service.voidTransaction(ref);

        assertThat(result.status()).isEqualTo(TaxProviderTransactionStatus.VOIDED);
        TaxProviderTransaction row = repository.findByReferenceId(ref).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(TaxProviderTransactionStatus.VOIDED);
    }

    @Test
    @DisplayName("commit is idempotent: the same referenceId twice yields exactly one committed row")
    void commitIsIdempotent() {
        UUID ref = UUID.randomUUID();

        service.commit(ref, "INVOICE");
        TaxProviderTransactionResult second = service.commit(ref, "INVOICE");

        assertThat(second.status()).isEqualTo(TaxProviderTransactionStatus.COMMITTED);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findByReferenceId(ref).orElseThrow().getAttempts())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("commit failure records PENDING_COMMIT without throwing (estimate-and-true-up)")
    void commitFailureRecordsPendingCommit() {
        UUID ref = UUID.randomUUID();
        provider.failCommit = true;

        TaxProviderTransactionResult result = service.commit(ref, "INVOICE");

        assertThat(result.status()).isEqualTo(TaxProviderTransactionStatus.PENDING_COMMIT);
        TaxProviderTransaction row = repository.findByReferenceId(ref).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(TaxProviderTransactionStatus.PENDING_COMMIT);
        assertThat(row.getLastError()).contains("provider down");
        assertThat(service.pendingCommitBacklog()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("re-commit job promotes PENDING_COMMIT to COMMITTED when the provider recovers")
    void recommitPromotesPendingToCommitted() {
        UUID ref = UUID.randomUUID();
        provider.failCommit = true;
        service.commit(ref, "INVOICE");
        assertThat(service.pendingCommitBacklog()).isEqualTo(1.0);

        // Provider recovers.
        provider.failCommit = false;
        service.recommitPending();

        TaxProviderTransaction row = repository.findByReferenceId(ref).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(TaxProviderTransactionStatus.COMMITTED);
        assertThat(row.getExternalTransactionId()).isEqualTo("ext-123");
        assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(service.pendingCommitBacklog()).isEqualTo(0.0);
    }

    /** Test double whose commit can be toggled to fail. */
    private static final class ControllableProvider implements TaxProviderClient {
        private boolean failCommit;

        @Override
        @NonNull
        public String providerName() {
            return "FAKE";
        }

        @Override
        @NonNull
        public TaxCalculationResponse estimate(@NonNull TaxCalculationRequest request) {
            throw new UnsupportedOperationException("not used in lifecycle test");
        }

        @Override
        @NonNull
        public TaxCalculationResponse refund(
                @NonNull TaxCalculationRequest request, @NonNull UUID originalReferenceId) {
            throw new UnsupportedOperationException("not used in lifecycle test");
        }

        @Override
        @NonNull
        public TaxProviderTransactionResult commit(@NonNull UUID referenceId) {
            if (failCommit) {
                throw new TaxCalculationException("provider down");
            }
            return new TaxProviderTransactionResult(
                    referenceId, TaxProviderTransactionStatus.COMMITTED, "ext-123", "ok");
        }

        @Override
        @NonNull
        public TaxProviderTransactionResult voidTransaction(@NonNull UUID referenceId) {
            return new TaxProviderTransactionResult(referenceId, TaxProviderTransactionStatus.VOIDED, "ext-123", "ok");
        }
    }
}
