package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.JobRole;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** The current tenant's job-role list (durion#2157), scoped by Hibernate's tenant filter. */
public interface JobRoleRepository extends JpaRepository<JobRole, UUID> {

    @NonNull
    List<JobRole> findAllByActiveTrueOrderByNameAsc();

    boolean existsByCodeIgnoreCase(@NonNull String code);
}
