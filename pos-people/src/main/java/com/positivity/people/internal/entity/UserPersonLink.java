package com.positivity.people.internal.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_person_links", uniqueConstraints = @UniqueConstraint(columnNames = { "user_id", "person_id" }))
@Getter
@Setter
public class UserPersonLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    @NonNull
    private String userId;

    @Column(name = "person_id", nullable = false)
    @NonNull
    private UUID personId;

    @Column(name = "link_type", length = 50)
    private String linkType = "PRIMARY";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "notes", length = 1000)
    private String notes;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
