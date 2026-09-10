package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.ExtTenant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtTenantRepository extends JpaRepository<ExtTenant, UUID> {

    Optional<ExtTenant> findBySlug(@NonNull String slug);

    @NonNull
    List<ExtTenant> findByStatusOrderByTenantIdAsc(@NonNull String status);
}
