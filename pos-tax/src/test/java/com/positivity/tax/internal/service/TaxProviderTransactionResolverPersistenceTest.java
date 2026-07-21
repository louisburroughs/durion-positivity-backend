package com.positivity.tax.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tax.common.enums.TaxProviderTransactionStatus;
import com.positivity.tax.internal.repository.TaxProviderTransactionRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Spring-context-backed coverage for {@link TaxProviderTransactionResolver} (#986, Copilot
 * finding 2 on {@code TaxProviderLifecycleServiceTest.java:65}).
 *
 * <p>The sibling unit test {@code TaxProviderLifecycleServiceTest} constructs the resolver with
 * {@code new TaxProviderTransactionResolver(repository)}, which bypasses Spring AOP: the
 * {@code @Transactional} boundary is never woven and the resolver runs as a plain object. This
 * test instead {@link Import}s the real {@code @Component} so the container hands back the
 * transactional proxy, and exercises the atomic upsert / idempotency contract of
 * {@link TaxProviderTransactionResolver#resolveId} against a real H2 (PostgreSQL-mode)
 * DataSource with the actual Flyway {@code V2__tax_provider_transaction.sql} migration and
 * Hibernate schema validation — the same slice configuration the lifecycle test uses.
 *
 * <p>Named {@code ...PersistenceTest} (not {@code ...IT}/{@code ...ITCase}) on purpose: the
 * root failsafe config only claims {@code **&#47;*IT.java}/{@code **&#47;*ITCase.java}, so this
 * class is picked up by surefire and runs in the {@code mvn test} phase — i.e. inside the same
 * "Unit Tests (pos-tax)" CI job whose multi-minute hang motivated the resolver rework.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_tax_resolver;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.flyway.enabled=true",
            "spring.flyway.locations=classpath:db/migration"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TaxProviderTransactionResolver.class)
@ActiveProfiles("test")
class TaxProviderTransactionResolverPersistenceTest {

    @Autowired
    private TaxProviderTransactionRepository repository;

    @Autowired
    private TaxProviderTransactionResolver resolver;

    @Test
    @DisplayName("resolver bean is a Spring AOP proxy, so its @Transactional boundary is actually woven")
    void resolverBeanIsProxied() {
        // The whole point of the finding: unlike the `new`-constructed resolver in
        // TaxProviderLifecycleServiceTest, the container-managed bean is wrapped in a
        // transactional proxy, so resolveId() runs through the @Transactional advice.
        assertThat(AopUtils.isAopProxy(resolver)).isTrue();
    }

    @Test
    @DisplayName("resolveId is idempotent: same referenceId twice returns the same id and leaves exactly one row")
    void resolveIdIsIdempotent() {
        UUID ref = UUID.randomUUID();

        // First call takes the create path: findByReferenceId empty -> insertIfAbsent -> re-read.
        UUID first = resolver.resolveId(ref, "INVOICE", "FAKE");
        // Second call takes the fast adopt path: findByReferenceId hits the existing row.
        UUID second = resolver.resolveId(ref, "INVOICE", "FAKE");

        assertThat(first).isNotNull();
        assertThat(second).isEqualTo(first);
        // Exactly one row: proves no duplicate insert and no leaked UNIQUE(reference_id) violation.
        assertThat(repository.count()).isEqualTo(1L);
        assertThat(repository.findByReferenceId(ref).orElseThrow().getStatus())
                .isEqualTo(TaxProviderTransactionStatus.PENDING_COMMIT);
    }

    @Test
    @DisplayName("distinct referenceIds create distinct rows")
    void distinctReferencesCreateDistinctRows() {
        UUID refA = UUID.randomUUID();
        UUID refB = UUID.randomUUID();

        UUID idA = resolver.resolveId(refA, "INVOICE", "FAKE");
        UUID idB = resolver.resolveId(refB, "INVOICE", "FAKE");

        assertThat(idA).isNotEqualTo(idB);
        assertThat(repository.count()).isEqualTo(2L);
        assertThat(repository.findByReferenceId(refA).orElseThrow().getId()).isEqualTo(idA);
        assertThat(repository.findByReferenceId(refB).orElseThrow().getId()).isEqualTo(idB);
    }

    @Test
    @DisplayName("ON CONFLICT DO NOTHING is a no-op on a duplicate reference_id, and resolveId adopts the winner")
    void onConflictDoNothingAdoptsExistingWinner() {
        // Deterministic stand-in for the concurrent double-insert race. A true two-thread test
        // is intentionally omitted: @DataJpaTest wraps each method in a single rolled-back
        // transaction, and H2's in-memory locking makes two committed connections racing the
        // same UNIQUE(reference_id) key timing-dependent (spurious lock-timeout failures).
        // Instead we drive the exact SQL branch that the loser of that race hits — a second
        // INSERT ... ON CONFLICT DO NOTHING for an already-present reference_id — sequentially,
        // which is fully deterministic and still proves the storage-layer contract the resolver
        // relies on (portable no-op instead of a constraint violation that would poison the tx).
        UUID ref = UUID.randomUUID();
        Instant now = Instant.now();

        int winner = repository.insertIfAbsent(
                UUIDv7Generator.generate(),
                ref,
                "INVOICE",
                "FAKE",
                TaxProviderTransactionStatus.PENDING_COMMIT.name(),
                now);
        // The "loser": same reference_id, a different (never-colliding) primary key.
        int loser = repository.insertIfAbsent(
                UUIDv7Generator.generate(),
                ref,
                "INVOICE",
                "FAKE",
                TaxProviderTransactionStatus.PENDING_COMMIT.name(),
                now);

        assertThat(winner).isEqualTo(1);
        assertThat(loser).isEqualTo(0); // ON CONFLICT DO NOTHING: no second row, no exception.
        assertThat(repository.count()).isEqualTo(1L);

        UUID surviving = repository.findByReferenceId(ref).orElseThrow().getId();
        // resolveId over the pre-existing row must adopt the surviving id, never insert again.
        UUID resolved = resolver.resolveId(ref, "INVOICE", "FAKE");
        assertThat(resolved).isEqualTo(surviving);
        assertThat(repository.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("resolveId over the same referenceId never leaks an exception (upsert stays inside one tx)")
    void resolveIdNeverLeaksConstraintViolation() {
        UUID ref = UUID.randomUUID();
        resolver.resolveId(ref, "INVOICE", "FAKE");

        // Re-resolving must not surface a DataIntegrityViolationException from the UNIQUE index.
        assertThatCode(() -> resolver.resolveId(ref, "INVOICE", "FAKE")).doesNotThrowAnyException();
        assertThat(repository.count()).isEqualTo(1L);
    }
}
