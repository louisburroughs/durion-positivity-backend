package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.LaborTimeSourcePolicyEntity;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LaborTimeSourcePolicyRepository extends JpaRepository<LaborTimeSourcePolicyEntity, UUID> {

    @NonNull
    List<LaborTimeSourcePolicyEntity> findByEnabledTrue();
}
