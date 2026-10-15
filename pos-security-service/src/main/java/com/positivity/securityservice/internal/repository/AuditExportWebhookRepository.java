package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.AuditExportWebhook;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The bound tenant's audit export webhook destination (#2497). Hibernate's tenant predicate and
 * row-level security both apply, so the lookup only ever sees the bound tenant's row, of which there
 * is at most one ({@code UNIQUE (tenant_id)}).
 */
public interface AuditExportWebhookRepository extends JpaRepository<AuditExportWebhook, UUID> {

    /** The bound tenant's registered destination, if any. */
    @NonNull
    Optional<AuditExportWebhook> findFirstByOrderByRegisteredAtDesc();
}
