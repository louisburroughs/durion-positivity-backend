package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.BillingRules;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for BillingRules entity.
 * CAP:092 - Preferences & Billing Rules
 */
@Repository
public interface BillingRulesRepository extends JpaRepository<BillingRules, UUID> {

    @NonNull
    Optional<BillingRules> findByPartyId(@NonNull String partyId);

    boolean existsByPartyId(@NonNull String partyId);
}
