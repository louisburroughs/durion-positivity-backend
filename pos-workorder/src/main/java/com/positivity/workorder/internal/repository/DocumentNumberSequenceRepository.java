package com.positivity.workorder.internal.repository;

import com.positivity.tenancy.TenantAudited;
import com.positivity.workorder.internal.entity.DocumentNumberSequence;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentNumberSequenceRepository extends JpaRepository<DocumentNumberSequence, UUID> {

    /**
     * Load a scope's counter row under a pessimistic write lock ({@code SELECT ... FOR UPDATE}).
     * Concurrent allocators in the same scope wait here until the holding transaction commits.
     *
     * @return the locked row, or empty if the scope has never issued a number
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DocumentNumberSequence> findByScopeKey(@NonNull String scopeKey);

    /**
     * Create a scope's counter row unless one exists. {@code ON CONFLICT DO NOTHING} keeps a
     * concurrent first use from throwing, so the caller's transaction stays usable on one
     * connection (#2342); the target-less form runs on Postgres and on H2 in PostgreSQL mode.
     * Native SQL skips the entity callbacks, so every column is passed.
     *
     * @return 1 if this call inserted the row, 0 if the scope already had one
     */
    @TenantAudited(
            reason = "names the tenant explicitly (the caller's resolved tenant), so the row is the bound tenant's on"
                    + " Postgres and on the H2 slices alike; the policy's WITH CHECK still refuses any other tenant")
    @Modifying
    @Query(value = """
                    INSERT INTO document_number_sequence (tenant_id, id, scope_key, next_value, updated_at)
                    VALUES (:tenantId, :id, :scopeKey, :firstValue, :now)
                    ON CONFLICT DO NOTHING
                    """, nativeQuery = true)
    int insertIfAbsent(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("scopeKey") String scopeKey,
            @Param("firstValue") long firstValue,
            @Param("now") Instant now);
}
