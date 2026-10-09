package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.AssignedIdentifier;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Accounting's copy of one pos-people-contact user-person link (AP reads #2670; ADR-0044 §6), written only by {@code
 * people-contact.user-person-link.updated} and removed by {@code .removed}, guarded by the fact's {@code
 * aggregateVersion}. A username resolves to a person only through a link whose status is {@value #ACTIVE}. The
 * username is INTERNAL (ADR-0072), as the AP actor fields that carry it already are.
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
}
