package com.positivity.people.internal.entity;

import com.positivity.people.internal.enums.UserLinkStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NonNull;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Entity representing a link between a user and a person. Enforces a unique
 * constraint on
 * userId to ensure one-to-one mapping between users and persons. Allows
 * multiple links
 * per person to support scenarios like multiple authentication methods.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "user_person_links",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id"}),
        indexes = @Index(name = "idx_user_person_links_person_id", columnList = "person_id"))
@Getter
@Setter
public class UserPersonLink {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    @NonNull
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "person_id", nullable = false)
    @NonNull
    private Person person;

    public UUID getPersonId() {
        return person != null ? person.getId() : null;
    }

    @Column(name = "link_type", length = 50)
    private String linkType = "PRIMARY";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @NonNull
    private UserLinkStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "notes", length = 1000)
    private String notes;

    @PrePersist
    protected void onCreate() {
        if (status == null) {
            status = UserLinkStatus.ACTIVE;
        }
    }
}
