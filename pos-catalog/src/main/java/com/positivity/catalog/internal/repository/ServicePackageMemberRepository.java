package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ServicePackageMemberEntity;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServicePackageMemberRepository extends JpaRepository<ServicePackageMemberEntity, UUID> {

    @NonNull
    List<ServicePackageMemberEntity> findByPackageIdOrderBySequenceAsc(@NonNull UUID packageId);

    /** Batch read for a listing, so rendering N packages is two queries rather than N + 1. */
    @NonNull
    List<ServicePackageMemberEntity> findByPackageIdInOrderBySequenceAsc(@NonNull List<UUID> packageIds);

    boolean existsByPackageIdAndServiceId(@NonNull UUID packageId, @NonNull UUID serviceId);

    void deleteByPackageIdAndId(@NonNull UUID packageId, @NonNull UUID memberId);
}
