package com.positivity.vehiclefitment.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "model")
public class Model {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    private String name;

    @ManyToOne
    private Make make; // Reference to the Make entity

    private LocalDateTime cacheTimestamp;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
