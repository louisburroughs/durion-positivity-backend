package com.positivity.accounting.audit.repository;

import com.positivity.accounting.audit.entity.RefundPolicyConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for refund policy configurations.
 */
@Repository
public interface RefundPolicyConfigRepository extends JpaRepository<RefundPolicyConfig, UUID> {
    
    /**
     * Find the currently active refund policy.
     */
    @Query("SELECT c FROM RefundPolicyConfig c WHERE c.active = true ORDER BY c.createdAt DESC")
    Optional<RefundPolicyConfig> findActivePolicy();
}
