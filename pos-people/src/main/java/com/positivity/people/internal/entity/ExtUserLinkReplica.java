package com.positivity.people.internal.entity;

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
 * Read-only user-person-link replica fed by {@code people-contact.events.v1} (ADR-0044 §6, #875).
 * Serves username lookups for HR views (reports, approvals); pos-people-contact owns the facts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_people_contact_user_link")
public class ExtUserLinkReplica {

    @Id
    @Column(name = "link_id", nullable = false)
    private UUID linkId;

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): the primary key IS a
     * UUIDv7 minted by the owning module's envelope factory; this replica stores it verbatim.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
