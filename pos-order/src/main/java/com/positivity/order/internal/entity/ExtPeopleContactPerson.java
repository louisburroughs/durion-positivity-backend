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
import lombok.ToString;

/**
 * pos-order's copy of one pos-people-contact person (drawer actor names #2608; ADR-0044 §6), written only by {@code
 * people-contact.person.updated} and removed by {@code .person.deleted}, guarded by the fact's {@code
 * aggregateVersion}; a deletion leaves a tombstone ({@code deleted}, names cleared). It holds the first and last name
 * and nothing else (ADR-0072 minimisation): enough to put a name
 * beside a drawer actor's username. The names are CONFIDENTIAL, so {@code toString} leaves them out.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "ext_people_contact_person")
public class ExtPeopleContactPerson extends TenantScopedEntity {

    @Id
    @EqualsAndHashCode.Include
    @AssignedIdentifier("pos-people-contact's person id, carried on people-contact.person.updated as the aggregate;"
            + " minting one here would sever the copy from its owning fact and defeat the version guard")
    @Column(name = "person_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID personId;

    @ToString.Exclude
    @Column(name = "first_name", length = 255)
    private String firstName;

    @ToString.Exclude
    @Column(name = "last_name", length = 255)
    private String lastName;

    /** A tombstone of {@code person.deleted}: names cleared, kept so an older fact cannot bring the person back. */
    @Column(name = "deleted", nullable = false)
    private boolean deleted;

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
