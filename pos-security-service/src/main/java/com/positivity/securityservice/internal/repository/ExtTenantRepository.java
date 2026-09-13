package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.ExtTenant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtTenantRepository extends JpaRepository<ExtTenant, UUID> {

    Optional<ExtTenant> findBySlug(@NonNull String slug);

    @NonNull
    List<ExtTenant> findByStatusOrderByTenantIdAsc(@NonNull String status);

    /**
     * Organizations whose display name starts with {@code prefix}, or one of whose words does
     * (ADR-0062 §3). Anchored on both sides of the {@code OR} — never an unanchored substring — so
     * the directory cannot be swept with a single common fragment, and so the
     * {@code text_pattern_ops} index can serve the leading form. {@code ACTIVE} only: a tenant that
     * cannot be logged into is not offered.
     *
     * <p>{@code prefix} arrives with its {@code LIKE} wildcards already escaped (see
     * {@code TenantSearchService.escapeLike}), which the {@code ESCAPE} clauses below honour: a
     * query of {@code "%"} must match that character, not every organization in the registry.
     *
     * <p>Shortest name first, so an exact match outranks a longer name that merely starts the same.
     */
    @Query("""
            SELECT t FROM ExtTenant t
             WHERE t.status = :active
               AND t.displayNameKey IS NOT NULL
               AND (t.displayNameKey LIKE CONCAT(:prefix, '%') ESCAPE '\\'
                    OR t.displayNameKey LIKE CONCAT('% ', :prefix, '%') ESCAPE '\\')
             ORDER BY LENGTH(t.displayName), t.displayName, t.tenantId
            """)
    @NonNull
    List<ExtTenant> searchByDisplayNamePrefix(
            @Param("prefix") @NonNull String prefix, @Param("active") @NonNull String active, @NonNull Pageable page);
}
