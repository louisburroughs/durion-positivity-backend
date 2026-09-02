package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.LaborGuideImportEntity;
import com.positivity.catalog.internal.entity.LaborGuideImportEntity.Status;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LaborGuideImportRepository extends JpaRepository<LaborGuideImportEntity, UUID> {

    @NonNull
    List<LaborGuideImportEntity> findByStatusNotOrderByCreatedAtDesc(@NonNull Status status);

    @NonNull
    Optional<LaborGuideImportEntity> findFirstBySourceCodeAndStatusOrderByCompletedAtDesc(
            @NonNull String sourceCode, @NonNull Status status);
}
