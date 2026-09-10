package com.positivity.nhtsa.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantGlobal;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Data;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@Entity
@TenantGlobal(
        reason = "vehicle reference data shared by every tenant (ADR-0062 section 5, db/tenancy-global-tables.txt)")
@EntityListeners(AuditingEntityListener.class)
public class VehicleVariable {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    private String name;
    private String description;
    private LocalDateTime cacheTimestamp;
}
