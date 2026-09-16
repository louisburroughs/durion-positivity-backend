package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.ConflictRule;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read access to the seeded rule catalog; the application never writes it. */
public interface ConflictRuleRepository extends JpaRepository<ConflictRule, UUID> {
    Optional<ConflictRule> findByCode(@NonNull String code);
}
