package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.BaySpecialtyOperationEntity;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BaySpecialtyOperationRepository extends JpaRepository<BaySpecialtyOperationEntity, UUID> {

    /** The specialty codes a bay of this type is defaulted to on create (CAP-325 D14). */
    @NonNull
    List<BaySpecialtyOperationEntity> findByBayType(@NonNull String bayType);

    /**
     * Which bay types, if any, claim this operation. Empty means the operation is general work
     * — not "no bay can do it", which is the misreading the D14 default exists to prevent.
     */
    @NonNull
    List<BaySpecialtyOperationEntity> findByOperationCode(@NonNull String operationCode);
}
