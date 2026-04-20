package com.positivity.bulkloader.internal.repository;

import com.positivity.bulkloader.internal.entity.BulkLoadJob;
import com.positivity.bulkloader.internal.enums.JobStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BulkLoadJobRepository extends JpaRepository<BulkLoadJob, UUID> {

    List<BulkLoadJob> findByOperatorId(@NonNull String operatorId);

    Page<BulkLoadJob> findByOperatorId(@NonNull String operatorId, @NonNull Pageable pageable);

    Optional<BulkLoadJob> findByOperatorIdAndStatusIn(@NonNull String operatorId, @NonNull List<JobStatus> statuses);

    long countByOperatorIdAndStatusIn(@NonNull String operatorId, @NonNull List<JobStatus> statuses);
}
