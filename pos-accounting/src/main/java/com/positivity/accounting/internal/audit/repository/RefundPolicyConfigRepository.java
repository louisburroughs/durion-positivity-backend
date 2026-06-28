package com.positivity.accounting.internal.audit.repository;

import com.positivity.accounting.internal.audit.entity.RefundPolicyConfig;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Repository for refund policy configurations.
 */
public interface RefundPolicyConfigRepository extends JpaRepository<RefundPolicyConfig, UUID> {

    /**
     * Find the currently active refund policy.
     */
    @Query("SELECT c FROM RefundPolicyConfig c WHERE c.active = true ORDER BY c.createdAt DESC")
    Optional<RefundPolicyConfig> findActivePolicy();
}
