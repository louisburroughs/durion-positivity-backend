package com.positivity.bulkloader.internal.repository;

import com.positivity.bulkloader.internal.entity.BulkLoadRecordAudit;
import com.positivity.bulkloader.internal.enums.ReviewStatus;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BulkLoadRecordAuditRepository extends JpaRepository<BulkLoadRecordAudit, UUID> {

    List<BulkLoadRecordAudit> findByJobId(@NonNull UUID jobId);

    List<BulkLoadRecordAudit> findByJobIdAndReviewStatus(@NonNull UUID jobId, @NonNull ReviewStatus reviewStatus);

    long countByJobIdAndReviewStatus(@NonNull UUID jobId, @NonNull ReviewStatus reviewStatus);
}
