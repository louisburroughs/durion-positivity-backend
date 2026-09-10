package com.positivity.tenant.internal.repository;

import com.positivity.tenant.internal.entity.BillingProfileEntity;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingProfileRepository extends JpaRepository<BillingProfileEntity, UUID> {

    Optional<BillingProfileEntity> findByAccountId(@NonNull UUID accountId);
}
