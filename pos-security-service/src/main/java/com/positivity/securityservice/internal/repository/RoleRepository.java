package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.dto.RoleGrantRow;
import com.positivity.securityservice.internal.dto.RolePersonaDto;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.tenancy.TenantAudited;
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
     * The role a name resolves to under the same case-insensitive uniqueness {@link
     * #existsByNameIgnoreCase} enforces (ADR-0062 §6, WS8): the template's canonical name and a
     * tenant's differently-cased copy are one role, never two.
     *
     * <p>At most one row can match: {@code roles_tenant_lower_name_key} in the baseline is
     * {@code UNIQUE (tenant_id, lower(name))}, so the database refuses the second casing rather
     * than leaving this single-result query to fail on it.
     */
    Optional<Role> findByNameIgnoreCase(String name);

    /**
     * The bound tenant's template roles (ADR-0062 §6). Read under the platform binding this is
     * the role template every new tenant is provisioned from.
     */
    List<Role> findByTemplateKeyIsNotNullOrderByNameAsc();

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
     * Every {@code role_permissions} row of the given roles, each carrying the granting role's
     * {@code location_scope} and {@code location_hierarchy} (ADR-0061 §2, #1868).
     *
     * <p>The per-role form of {@link #findPermissionNamesByRoleNames}: that query's
     * {@code DISTINCT} collapse is right for {@code perm_bits} but loses which role granted what,
     * and the location-scope bitsets need exactly that. Same matching rules — case-insensitive on
     * a name the caller has already normalized — and the same fail-closed shape: an unknown role,
     * or one with no grants, yields no rows.
     *
     * <p>A constructor projection rather than a {@link Role} fetch for the same reason as
     * {@link #findAllPersonas()}: the four columns are what token issuance needs, and hydrating
     * the entity graph would materialize every {@code Permission} row of every role held.
     *
     * @param names role names, already normalized to upper case with any {@code ROLE_} prefix stripped
     * @return one row per (role, permission) grant; empty when no role matches or none has grants
     */
    @Query("""
            SELECT new com.positivity.securityservice.internal.dto.RoleGrantRow(
                r.name, r.locationScope, r.locationHierarchy, p.name)
            FROM Role r JOIN r.permissions p
            WHERE UPPER(r.name) IN :names
            """)
    List<RoleGrantRow> findGrantRowsByRoleNames(@Param("names") Collection<String> names);

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
    @TenantAudited(reason = "row-level security scopes role_permissions; role_id resolves within the bound tenant only")
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
