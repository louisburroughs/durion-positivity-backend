package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.AccountingTemplateState;
import com.positivity.tenancy.TenantAudited;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The bound tenant's accounting template state row (#2526); the tenant filter scopes every query. */
public interface AccountingTemplateStateRepository extends JpaRepository<AccountingTemplateState, UUID> {

    /** The bound tenant's state row, if the template applier has ever run for it. */
    @Query("SELECT s FROM AccountingTemplateState s")
    Optional<AccountingTemplateState> findCurrent();

    /**
     * The bound tenant's state row under {@code SELECT ... FOR UPDATE}: the applier holds it for the
     * length of a run. Must run inside an active transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AccountingTemplateState s")
    Optional<AccountingTemplateState> findCurrentForUpdate();

    /**
     * Creates the tenant's state row unless one exists, in the caller's transaction on the caller's
     * connection. {@code ON CONFLICT DO NOTHING} keeps a concurrent first run from throwing (the
     * caller's transaction is not marked rollback-only) and from needing a second pooled connection,
     * the shape {@code AccountingSequenceRepository.insertIfAbsent} settled on (#2342). On Postgres a
     * concurrent insert for the same tenant waits for the in-flight one to commit or roll back, then
     * does nothing, so the re-read under the lock finds the winner's row. Native SQL skips the
     * entity callbacks, so every column is passed.
     *
     * @return 1 if this call inserted the row, 0 if the tenant already had one
     */
    @TenantAudited(
            reason = "names the tenant explicitly (the caller's resolved tenant), so the row is the bound tenant's on"
                    + " Postgres and on the H2 slices alike; the policy's WITH CHECK still refuses any other tenant")
    @Modifying
    @Query(value = """
                    INSERT INTO accounting_template_state (tenant_id, state_id, created_count, adopted_count,
                        refreshed_count, conflict_count, withheld_count, version, created_at, modified_at)
                    VALUES (:tenantId, :stateId, 0, 0, 0, 0, 0, 0, :now, :now)
                    ON CONFLICT DO NOTHING
                    """, nativeQuery = true)
    int insertIfAbsent(@Param("tenantId") UUID tenantId, @Param("stateId") UUID stateId, @Param("now") Instant now);
}
