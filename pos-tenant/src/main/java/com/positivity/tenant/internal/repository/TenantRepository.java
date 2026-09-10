package com.positivity.tenant.internal.repository;

import com.positivity.tenant.internal.entity.TenantEntity;
import com.positivity.tenant.internal.enums.TenantStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<TenantEntity, UUID> {

    Optional<TenantEntity> findBySlug(@NonNull String slug);

    boolean existsBySlug(@NonNull String slug);

    @NonNull
    List<TenantEntity> findByAccountIdOrderByCreatedAtAsc(@NonNull UUID accountId);

    @NonNull
    List<TenantEntity> findByStatusOrderByCreatedAtAsc(@NonNull TenantStatus status);

    @NonNull
    List<TenantEntity> findAllByOrderByCreatedAtAsc();
}
