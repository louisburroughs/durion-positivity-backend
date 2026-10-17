package com.positivity.tenant.internal.repository;

import com.positivity.tenant.internal.entity.ReadingTierEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** The reading-tier catalog (#2619). */
public interface ReadingTierRepository extends JpaRepository<ReadingTierEntity, UUID> {

    Optional<ReadingTierEntity> findByCode(@NonNull String code);

    boolean existsByCode(@NonNull String code);

    @NonNull
    List<ReadingTierEntity> findAllByOrderByCodeAsc();
}
