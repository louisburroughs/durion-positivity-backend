package com.positivity.order.internal.entity;

import com.positivity.shared.id.AssignedIdentifier;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * pos-order's copy of one pos-people-contact user-person link (drawer actor names #2608; ADR-0044 §6), written only by {@code
 * people-contact.user-person-link.updated} and tombstoned ({@value #REMOVED}) by {@code .removed}, guarded by the fact's {@code
 * aggregateVersion}. A username resolves to a person only through a link whose status is {@value #ACTIVE}. The
 * username is INTERNAL (ADR-0072), as the drawer actor fields that carry it already are.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "ext_people_contact_user_link")
public class ExtPeopleContactUserLink extends TenantScopedEntity {

    /** The only status through which a username names a person. */
    public static final String ACTIVE = "ACTIVE";

    /** The status a {@code user-person-link.removed} leaves: a tombstone that an older update cannot revive. */
    public static final String REMOVED = "REMOVED";

    @Id
    @EqualsAndHashCode.Include
    @AssignedIdentifier("pos-people-contact's link id, carried on people-contact.user-person-link.updated as the"
            + " aggregate; minting one here would sever the copy from its owning fact and defeat the version guard")
    @Column(name = "link_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID linkId;

    @Column(name = "person_id", columnDefinition = "UUID", nullable = false)
    private UUID personId;

    @Column(name = "username", length = 255, nullable = false)
    private String username;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    /** When this copy was last written. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** ArchUnit UUIDv7 rule hook: the id is a UUIDv7 issued by pos-people-contact. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
