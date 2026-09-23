package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.EmployeeRoleAssignmentDto;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * Reads the {@code ext_role_assignment_replica} replica (ADR-0044 §6, durion#2155/#2160) for the
 * employee register's "application roles" column.
 *
 * <p>Batched by design: the register renders one page of employees at a time, and the entire
 * point of the replica is to answer a page's worth of role lookups in one query instead of one
 * call per row — see the migration's header for the call-count math this avoids. There is
 * deliberately no single-username overload; a caller with one employee passes a singleton
 * collection.
 */
public interface RoleAssignmentReplicaService {

    /**
     * The active role assignments (DECISION-PEOPLE-026: not revoked, currently inside the
     * assignment's effective window) for every given username, keyed by username. A username with
     * no active assignment is absent from the map, not mapped to an empty list. Usernames are
     * matched via {@code ExtUserLinkReplica} upstream of this call — this method takes usernames
     * directly, already resolved from the page's employees.
     */
    @NonNull
    Map<String, List<EmployeeRoleAssignmentDto>> findActiveRoleAssignmentsByUsernames(
            @NonNull Collection<String> usernames);
}
