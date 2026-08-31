package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.dto.RolePersonaDto;
import com.positivity.securityservice.internal.entity.Role;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Every role's MCP persona metadata, ordered by rank then name (#1613).
     *
     * <p>A constructor projection rather than a {@link Role} fetch on purpose: {@code permissions}
     * is an {@code EAGER} {@code @ManyToMany}, so loading the entity graph for a sync that reads six
     * scalar columns would pull every grant of every role on every refresh.
     *
     * <p>{@code NULLS LAST} implements D2 — an unranked role sorts after every ranked one, but is
     * still returned, so it resolves to its own persona rather than the consumer's fallback.
     *
     * <p>Ineligible roles are included and flagged rather than filtered out. The consumer needs to
     * tell a role that is excluded by design from one it has never heard of; only the second is a
     * sync failure.
     */
    @Query("""
            SELECT new com.positivity.securityservice.internal.dto.RolePersonaDto(
                r.name, r.description, r.personaTitle, r.personaFocus, r.personaTone,
                r.mcpPersonaRank, r.mcpPersonaEligible)
            FROM Role r
            ORDER BY r.mcpPersonaRank ASC NULLS LAST, r.name ASC
            """)
    List<RolePersonaDto> findAllPersonas();

    /**
     * Records who granted a role-permission row and when (#1512).
     *
     * <p>Native because {@code role_permissions} is mapped as a plain {@code @ManyToMany} join
     * table on {@link Role#getPermissions()}; Hibernate writes only the two key columns, so the
     * provenance columns V30 added are unreachable through the entity model. Restructuring the
     * association into an entity would change how every caller reads grants, for two columns
     * nothing in the resolution path consults.
     *
     * <p>{@code flushAutomatically} matters: the join-table INSERT is still pending in the
     * persistence context when this runs, and without the flush the UPDATE would match no row.
     *
     * <p>Callers must pass only permissions the call actually added. Re-stamping a grant that
     * was already there would rewrite history: the actor who re-asserted an existing permission
     * is not the actor who granted it.
     *
     * @param permissionIds permissions newly granted to the role in this transaction
     * @return the number of grant rows stamped
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value = "UPDATE role_permissions SET granted_at = :grantedAt, granted_by = :grantedBy"
                    + " WHERE role_id = :roleId AND permission_id IN (:permissionIds)",
            nativeQuery = true)
    int recordGrantProvenance(
            @Param("roleId") @NonNull UUID roleId,
            @Param("permissionIds") @NonNull Collection<UUID> permissionIds,
            @Param("grantedBy") @NonNull String grantedBy,
            @Param("grantedAt") @NonNull Instant grantedAt);
}
