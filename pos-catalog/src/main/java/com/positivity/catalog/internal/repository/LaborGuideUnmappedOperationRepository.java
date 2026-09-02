package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.LaborGuideUnmappedOperationEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LaborGuideUnmappedOperationRepository extends JpaRepository<LaborGuideUnmappedOperationEntity, UUID> {

    @NonNull
    Optional<LaborGuideUnmappedOperationEntity> findBySourceCodeAndProviderOpCode(
            @NonNull String sourceCode, @NonNull String providerOpCode);

    @NonNull
    List<LaborGuideUnmappedOperationEntity> findAllByOrderByLastSeenAtDesc();
}
