package com.positivity.nhtsa.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Data;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

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
