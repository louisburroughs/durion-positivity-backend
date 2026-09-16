package com.positivity.shopmanager.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Shop extends TenantScopedEntity {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    private String name;
    private String address;

    /**
     * This module's own locally-maintained timezone value, read today by {@code
     * /v1/schedules/view}. As of #2023, {@link ExtLocationReplica#getTimezone()} carries the same
     * fact from pos-location, the actual owner, and supersedes this column for any <em>new</em>
     * read. Switching {@code /v1/schedules/view} itself over is deliberately out of scope here —
     * that endpoint's output must stay bit-for-bit unchanged.
     */
    @Column(length = 64)
    private String timezone;

    @OneToMany(mappedBy = "shop", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Technician> technicians;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
