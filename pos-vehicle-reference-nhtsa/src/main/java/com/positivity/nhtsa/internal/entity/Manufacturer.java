package com.positivity.nhtsa.internal.entity;

import jakarta.persistence.*;
import com.positivity.shared.id.UUIDv7Generator;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import com.positivity.shared.id.UUIDv7Id;
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
public class Manufacturer {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;
    private String name;
    private LocalDateTime cacheTimestamp;
}

