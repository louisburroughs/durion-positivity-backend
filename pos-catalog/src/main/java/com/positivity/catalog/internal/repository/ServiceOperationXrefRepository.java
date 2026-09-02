package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ServiceOperationXrefEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceOperationXrefRepository extends JpaRepository<ServiceOperationXrefEntity, UUID> {

    @NonNull
    Optional<ServiceOperationXrefEntity> findBySourceCodeAndProviderOpCode(
            @NonNull String sourceCode, @NonNull String providerOpCode);

    @NonNull
    Optional<ServiceOperationXrefEntity> findBySourceCodeAndServiceId(
            @NonNull String sourceCode, @NonNull UUID serviceId);

    @NonNull
    List<ServiceOperationXrefEntity> findBySourceCode(@NonNull String sourceCode);
}
