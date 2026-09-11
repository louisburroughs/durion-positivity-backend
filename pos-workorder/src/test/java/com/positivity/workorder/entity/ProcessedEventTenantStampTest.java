package com.positivity.workorder.entity;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.workorder.internal.entity.ProcessedEvent;
import com.positivity.workorder.internal.repository.ProcessedEventRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

/**
 * Regression proof for the ledger's tenant stamp (ADR-0062 §3, PR #1952 review): every listener
 * assigns the {@code eventId} key and calls {@code repository.save(...)}, which Spring Data routes
 * through {@code EntityManager.merge()} for an entity with an assigned id and no {@code Persistable}
 * / version. JPA §3.5.3 requires {@code @PrePersist} to run on the managed copy a merge creates
 * for a new entity, so the tenant bound to the thread must reach the row on exactly that path.
 */
@DataJpaTest(properties = "spring.flyway.enabled=false")
class ProcessedEventTenantStampTest {

    @Autowired
    private ProcessedEventRepository repository;

    @Autowired
    private EntityManager entityManager;

    private static ProcessedEvent row(String eventId) {
        return ProcessedEvent.builder()
                .eventId(eventId)
                .owner("customer")
                .processedAt(Instant.parse("2026-09-11T10:00:00Z"))
                .build();
    }

    private ProcessedEvent reload(String eventId) {
        entityManager.flush();
        entityManager.clear();
        return repository.findById(eventId).orElseThrow();
    }

    @Test
    @DisplayName("save() with an assigned eventId (the merge path) stamps the bound tenant on the row")
    void saveUnderBoundTenantStampsTenantThroughMerge() {
        String eventId = UUID.randomUUID().toString();

        ProcessedEvent saved = asTenant(TENANT_A, () -> repository.save(row(eventId)));

        // The managed copy merge() returned carries the stamp, and so does the persisted row.
        assertThat(saved.getTenantId()).isEqualTo(TENANT_A);
        assertThat(reload(eventId).getTenantId()).isEqualTo(TENANT_A);
    }

    @Test
    @DisplayName("save() with no tenant bound leaves tenant_id null: the row belongs to no manifest")
    void saveUnboundLeavesTenantNull() {
        String eventId = UUID.randomUUID().toString();

        repository.save(row(eventId));

        assertThat(reload(eventId).getTenantId()).isNull();
    }

    @Test
    @DisplayName("a tenant set explicitly by the caller wins over the bound tenant")
    void explicitTenantIsKept() {
        String eventId = UUID.randomUUID().toString();
        ProcessedEvent explicit = row(eventId);
        explicit.setTenantId(TENANT_B);

        asTenant(TENANT_A, () -> repository.save(explicit));

        assertThat(reload(eventId).getTenantId()).isEqualTo(TENANT_B);
    }

    @Test
    @DisplayName("the per-tenant window scan sees only rows stamped with the queried tenant")
    void windowScanIsScopedToTheStampedTenant() {
        String forA = "01990000-0000-7000-8000-00000000000a";
        String forB = "01990000-0000-7000-8000-00000000000b";
        asTenant(TENANT_A, () -> repository.save(row(forA)));
        asTenant(TENANT_B, () -> repository.save(row(forB)));
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findEventIdsInRange(
                        "customer",
                        TENANT_A,
                        "01990000-0000-7000-8000-000000000000",
                        "01990000-0000-7000-8000-0000000000ff"))
                .containsExactly(forA);
        assertThat(repository.findEventIdsInRange(
                        "customer",
                        TENANT_B,
                        "01990000-0000-7000-8000-000000000000",
                        "01990000-0000-7000-8000-0000000000ff"))
                .containsExactly(forB);
    }
}
