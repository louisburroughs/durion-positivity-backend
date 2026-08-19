package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.Role;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleRepository extends JpaRepository<Role, UUID> {
    Optional<Role> findByName(String name);

    boolean existsByName(String name);

    /** Story #62: case-insensitive duplicate check for role name uniqueness. */
    boolean existsByNameIgnoreCase(String name);

    /**
     * Names of every permission granted to the given roles through {@code role_permissions}.
     *
     * <p>Backs JWT authority resolution, so it is deliberately a projection rather than a
     * fetch of the {@link Role} graph: token issuance needs the permission names only.
     * Role names that do not exist contribute nothing, which keeps ungranted principals
     * failing closed.
     *
     * <p>Matched case-insensitively: callers normalize to upper case, but nothing stops a
     * role being created through the admin API under a different casing, and a case-sensitive
     * match would silently resolve zero grants for it.
     *
     * <p>{@code DISTINCT} because roles overlap heavily — {@code mcp:chat:execute} alone is
     * granted to nearly every role — so a multi-role principal would otherwise make the
     * database materialize the same name once per granting role on every token issuance.
     *
     * @param names role names, already normalized to upper case with any {@code ROLE_} prefix stripped
     * @return the granted permission names; empty when no role matches or no role has grants
     */
    @Query("SELECT DISTINCT p.name FROM Role r JOIN r.permissions p WHERE UPPER(r.name) IN :names")
    Set<String> findPermissionNamesByRoleNames(@Param("names") Collection<String> names);
}
