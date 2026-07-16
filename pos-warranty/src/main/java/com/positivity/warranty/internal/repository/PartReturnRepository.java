package com.positivity.warranty.internal.repository;

import com.positivity.warranty.internal.entity.PartReturn;
import com.positivity.warranty.internal.enums.PartReturnStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartReturnRepository extends JpaRepository<PartReturn, UUID> {

    /** Hold-shelf / shipping worklist (PRD §8 {@code GET /v1/warranty/part-returns?status=}). */
    @NonNull
    List<PartReturn> findByStatus(@NonNull PartReturnStatus status);

    @NonNull
    List<PartReturn> findByClaimId(@NonNull UUID claimId);

    /** At most one part return per claim line (PRD §3.8). */
    @NonNull
    Optional<PartReturn> findByClaimLineId(@NonNull UUID claimLineId);
}
