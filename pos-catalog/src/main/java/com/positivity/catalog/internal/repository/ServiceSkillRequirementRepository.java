package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ServiceSkillRequirementEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Skill requirements (CAP-329), the profile's children, looked up by service id. */
public interface ServiceSkillRequirementRepository extends JpaRepository<ServiceSkillRequirementEntity, UUID> {

    @NonNull
    List<ServiceSkillRequirementEntity> findAllByServiceIdOrderBySkillIdAsc(@NonNull UUID serviceId);

    @NonNull
    List<ServiceSkillRequirementEntity> findAllByServiceIdIn(@NonNull Collection<UUID> serviceIds);

    void deleteAllByServiceId(@NonNull UUID serviceId);
}
