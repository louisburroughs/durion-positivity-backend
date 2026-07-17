package com.positivity.location.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
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
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Read-only person identity replica fed by {@code people-contact.events.v1} (ADR-0044 §6, #885):
 * resolves {@code location.responsible_person_id} to display names and contact details without a
 * synchronous call into the people domain. Only {@link
 * com.positivity.location.internal.service.PeopleContactEventsListener} writes this table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "ext_people_contact_person")
public class ExtPersonReplica {

    @Id
    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "primary_email")
    private String primaryEmail;

    /** Serialized {@code List<PersonUpdatedV1.ContactPointV1>} snapshot (type, value, primary). */
    @Column(name = "contact_points", columnDefinition = "text")
    private String contactPoints;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** ArchUnit UUIDv7 rule hook (ADR-0013): the key is the owner's UUIDv7, stored verbatim. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
