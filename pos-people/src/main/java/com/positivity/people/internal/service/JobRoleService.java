package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.CreateJobRoleRequest;
import com.positivity.people.internal.dto.JobRoleDto;
import java.util.List;
import org.jspecify.annotations.NonNull;

/** The calling tenant's own job-role list (durion#2157): HR master data, never a permission. */
public interface JobRoleService {

    /** Every active job role on the tenant's list, ordered by name. */
    @NonNull
    List<JobRoleDto> listActive();

    /**
     * Adds a job role to the tenant's list.
     *
     * @throws com.positivity.people.internal.exception.SemanticValidationException when the code
     *     is already used by another job role in this tenant
     */
    @NonNull
    JobRoleDto create(@NonNull CreateJobRoleRequest request);
}
