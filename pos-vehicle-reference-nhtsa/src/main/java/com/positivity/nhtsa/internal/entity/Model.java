package com.positivity.nhtsa.internal.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import com.positivity.shared.id.UUIDv7Id;
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
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

}

