package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "service")
public class ServiceEntity implements CatalogItem {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    private String name;
    private String longDescription;
    private String shortDescription;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * The fact-envelope aggregate version (#1486). JPA optimistic-lock counter that strictly
     * increments on every committed mutation, so it can never tie the way the legacy
     * {@code updatedAt}-epoch-millis convention could when two mutations landed in the same
     * millisecond. Seeded from those legacy values by migration V15 so the published sequence
     * never regresses for consumers already holding a replica.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Override
    public String getLongDescription() {
        return this.longDescription;
    }

    @Override
    public void setId(UUID id) {
        this.id = id;
    }
}
