package com.positivity.bulkloader.internal.repository;

import com.positivity.bulkloader.internal.entity.BulkLoadColumnMapping;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BulkLoadColumnMappingRepository extends JpaRepository<BulkLoadColumnMapping, UUID> {

    List<BulkLoadColumnMapping> findByJobId(@NonNull UUID jobId);

    void deleteByJobId(@NonNull UUID jobId);
}
