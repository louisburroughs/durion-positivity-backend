package com.positivity.securityservice.internal.entity;

import com.positivity.securityservice.internal.enums.LocationHierarchy;
import com.positivity.securityservice.internal.enums.LocationScope;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "roles")
public class Role extends TenantScopedEntity {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(unique = true, nullable = false)
    private String name;

    @Column(length = 500)
    private String description;

    /**
     * Permissions assigned to this role
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "role_permissions",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new HashSet<>();

    /**
     * Structured MCP persona slots (#1613). pos-mcp-server renders these into its own persona
     * template; deliberately not prompt text, so prompt wording stays an MCP concern and a role
     * author cannot inject instructions into an assembled prompt.
     *
     * <p>Null means "derive it" — pos-mcp-server falls back to the humanized role name, the
     * description, and a neutral tone, so a role is never required to carry curated fields.
     */
    @Column(name = "persona_title", length = 60)
    private String personaTitle;

    @Column(name = "persona_focus", length = 200)
    private String personaFocus;

    @Column(name = "persona_tone", length = 120)
    private String personaTone;

    /**
     * MCP persona resolution priority, lowest first. Null sorts after every ranked role but still
     * ahead of the {@code ROLE_USER} fallback.
     */
    @Column(name = "mcp_persona_rank")
    private Short mcpPersonaRank;

    /**
     * Whether this role participates in MCP persona resolution at all (#1613). False for roles with
     * no MCP access, so their callers land on the fallback by design rather than being counted as a
     * sync failure.
     */
    // @ColumnDefault mirrors the DEFAULT TRUE in V34 into Hibernate-generated DDL. Without it the
    // H2 test schema (ddl-auto=create-drop, Flyway disabled) gets NOT NULL with no default, and any
    // insert that does not name this column — a seed migration, or a test writing through plain JDBC
    // — fails where the real Postgres schema would have accepted it.
    @ColumnDefault("true")
    @Column(name = "mcp_persona_eligible", nullable = false)
    private boolean mcpPersonaEligible = true;

    /**
     * Whether this role's grants reach every location or only the holder's assigned nodes
     * (ADR-0061 §1, #1868). {@code ALL} preserves today's behaviour and is the default for a role
     * created through the API; {@code LOCATION} only takes effect at endpoints that check the
     * {@code loc_fin_bits} / {@code loc_oth_bits} / {@code loc_scope} claims.
     */
    // @ColumnDefault mirrors V37's DEFAULT into the H2 test schema for the same reason as
    // mcp_persona_eligible above: inserts that do not name the column must still succeed.
    @ColumnDefault("'ALL'")
    @Enumerated(EnumType.STRING)
    @Column(name = "location_scope", nullable = false, length = 16)
    private LocationScope locationScope = LocationScope.ALL;

    /**
     * Which pos-location parent dimension a {@code LOCATION}-scoped role is evaluated along
     * (ADR-0061 §2). Meaningful only when {@link #locationScope} is {@code LOCATION}; kept
     * populated regardless so the value is already right if the scope is later narrowed.
     */
    @ColumnDefault("'OTHER'")
    @Enumerated(EnumType.STRING)
    @Column(name = "location_hierarchy", nullable = false, length = 16)
    private LocationHierarchy locationHierarchy = LocationHierarchy.OTHER;

    @CreatedDate
    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false, length = 255)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * Time when this role was last modified (for audit purposes).
     * Differs slightly from database-managed updatedAt to allow tracking changes
     * that don't trigger an update (e.g. permission changes).
     */
    private Instant lastModifiedAt;

    @Column(length = 255)
    private String lastModifiedBy;
}
