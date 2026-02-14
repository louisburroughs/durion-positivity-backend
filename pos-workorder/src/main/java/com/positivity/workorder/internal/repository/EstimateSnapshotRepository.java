package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.EstimateSnapshot;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EstimateSnapshotRepository extends JpaRepository<EstimateSnapshot, UUID> {

    /**
     * Find all snapshots for a given estimate, ordered by capture time descending.
     */
    @NonNull
    List<EstimateSnapshot> findByEstimateIdOrderByCapturedAtDesc(@NonNull UUID estimateId);
}
