package com.positivity.tenant.internal.repository;

import com.positivity.tenant.internal.entity.ReadingTierEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The reading-tier catalog (#2619). */
public interface ReadingTierRepository extends JpaRepository<ReadingTierEntity, UUID> {

    Optional<ReadingTierEntity> findByCode(@NonNull String code);

    /**
     * The tier row under a write lock (#2619 review): a tenant assignment and a reprice or deactivation of the same
     * tier serialise here, so an assignment never publishes a pre-reprice amount and a deactivation never races an
     * assignment onto the tier.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM ReadingTierEntity t WHERE t.code = :code")
    Optional<ReadingTierEntity> lockByCode(@Param("code") @NonNull String code);

    boolean existsByCode(@NonNull String code);

    @NonNull
    List<ReadingTierEntity> findAllByOrderByCodeAsc();
}
