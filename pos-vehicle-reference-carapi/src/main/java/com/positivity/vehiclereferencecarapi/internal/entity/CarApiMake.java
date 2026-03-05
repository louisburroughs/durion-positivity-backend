package com.positivity.vehiclereferencecarapi.internal.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import com.positivity.shared.id.UUIDv7Id;
@Entity
@EntityListeners(AuditingEntityListener.class)
@Data
public class CarApiMake {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;
    private String makeId;
    private String makeName;
    private LocalDateTime cacheTimestamp;
}

