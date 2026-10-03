package com.positivity.platformsender.internal.entity;

import com.positivity.shared.id.AssignedIdentifier;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * ADR-0044 R3 replica of pos-people-contact's person contact points, reduced to the two this module
 * delivers to. Values are kept as the owner stored them and normalized at send time. Written only by
 * {@code PeopleContactEventsListener}; both addresses are null on a deleted person (a versioned
 * tombstone).
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "ext_people_contact_person")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtPeopleContactPerson extends TenantScopedEntity {

    /** pos-people-contact's person id, assigned by the owner. */
    @Id
    @AssignedIdentifier("pos-people-contact's person id; the replica keys on the owner's id and never invents one")
    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    /** The primary {@code EMAIL} contact point, else the first one. */
    @Column(name = "email")
    private String email;

    /** The primary {@code PHONE_MOBILE} contact point, else the first one. */
    @Column(name = "mobile_phone")
    private String mobilePhone;

    /** The owner's emission timestamp (epoch ms), a last-writer-wins hint per person. */
    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the module's ArchUnit UUIDv7 rule: the key is a UUIDv7 minted by
     * pos-people-contact and stored verbatim ({@link AssignedIdentifier}), never generated here.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
