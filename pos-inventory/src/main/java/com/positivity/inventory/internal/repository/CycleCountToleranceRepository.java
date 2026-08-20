package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.CycleCountTolerance;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link CycleCountTolerance} rows.
 *
 * <p>The four finders below are the four scopes {@code CycleCountToleranceResolver} tries in
 * most-specific-first order; each is a plain equality lookup (including on the {@code IS NULL}
 * side) rather than one combined query, since JPQL's null-vs-equality handling for an optional
 * parameter is easy to get subtly wrong and these are simple enough not to need it.
 */
public interface CycleCountToleranceRepository extends JpaRepository<CycleCountTolerance, UUID> {

    @NonNull
    Optional<CycleCountTolerance> findByProductIdAndStorageLocationAndActiveTrue(
            @NonNull UUID productId, @NonNull String storageLocation);

    @NonNull
    Optional<CycleCountTolerance> findByProductIdAndStorageLocationIsNullAndActiveTrue(@NonNull UUID productId);

    @NonNull
    Optional<CycleCountTolerance> findByProductIdIsNullAndStorageLocationAndActiveTrue(@NonNull String storageLocation);

    @NonNull
    Optional<CycleCountTolerance> findByProductIdIsNullAndStorageLocationIsNullAndActiveTrue();

    @NonNull
    List<CycleCountTolerance> findByProductIdAndActiveTrue(@NonNull UUID productId);

    @NonNull
    List<CycleCountTolerance> findByStorageLocationAndActiveTrue(@NonNull String storageLocation);

    @NonNull
    List<CycleCountTolerance> findAllByOrderByCreatedAtDesc();
}
