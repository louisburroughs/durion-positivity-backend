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

    boolean existsByDisplayNameKey(@NonNull String displayNameKey);

    @NonNull
    List<TenantEntity> findByAccountIdOrderByCreatedAtAsc(@NonNull UUID accountId);

    @NonNull
    List<TenantEntity> findByStatusOrderByCreatedAtAsc(@NonNull TenantStatus status);

    @NonNull
    List<TenantEntity> findAllByOrderByCreatedAtAsc();

    /** The tenants on a reading tier that still count (#2619): every status but {@code DECOMMISSIONED}. */
    @NonNull
    List<TenantEntity> findByReadingTierCodeAndStatusNotOrderByCreatedAtAsc(
            @NonNull String readingTierCode, @NonNull TenantStatus status);

    boolean existsByReadingTierCodeAndStatusNot(@NonNull String readingTierCode, @NonNull TenantStatus status);
}
