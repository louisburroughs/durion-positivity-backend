package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.GuardrailPolicyEntity;
import com.positivity.catalog.internal.entity.GuardrailPolicyScope;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuardrailPolicyRepository extends JpaRepository<GuardrailPolicyEntity, UUID> {

    Optional<GuardrailPolicyEntity> findTopByScopeAndScopeIdOrderByCreatedAtDesc(
            GuardrailPolicyScope scope,
            UUID scopeId);
}
