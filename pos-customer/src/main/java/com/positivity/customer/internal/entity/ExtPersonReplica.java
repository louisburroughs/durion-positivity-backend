package com.positivity.customer.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
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
import lombok.NoArgsConstructor;

/**
 * Read-only person identity replica fed by {@code people-contact.events.v1} (ADR-0044 §6, #877).
 * {@code contactPoints} holds the full typed contact list as JSON (CRM flows surface arbitrary
 * contact types); {@code primaryEmail} is flattened for search and matching.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_people_contact_person")
public class ExtPersonReplica {

    @Id
    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "preferred_name")
    private String preferredName;

    @Column(name = "primary_email")
    private String primaryEmail;

    /** Serialized {@code List<PersonUpdatedV1.ContactPointV1>} JSON. */
    @Column(name = "contact_points", columnDefinition = "text")
    private String contactPoints;

    @Column(name = "person_created_at")
    private Instant personCreatedAt;

    @Column(name = "person_updated_at")
    private Instant personUpdatedAt;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): the primary key IS a
     * UUIDv7 minted by the owning module; this replica stores it verbatim.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
