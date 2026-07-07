package com.positivity.inventory.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Local reference copy of a location owned by pos-location (ADR-0016),
 * maintained by the location sync flow (CAP-214 #40). Other inventory
 * records reference {@code locationId}; pos-location remains the source
 * of truth for identity, name, status, and timezone.
 */
@Entity
@Table(name = "location_ref")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class LocationRefEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "location_ref_id", updatable = false, nullable = false)
    private UUID locationRefId;

    @Column(name = "location_id", nullable = false, unique = true, updatable = false)
    private UUID locationId;

    @Column(name = "hr_location_id")
    private String hrLocationId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "code")
    private String code;

    @Column(name = "status", nullable = false, length = 50)
    private String status;

    @Column(name = "timezone", length = 100)
    private String timezone;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "source_updated_at")
    private Instant sourceUpdatedAt;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
