package com.positivity.vehiclereferencecarapi.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantGlobal;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Data;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@TenantGlobal(
        reason = "vehicle reference data shared by every tenant (ADR-0062 section 5, db/tenancy-global-tables.txt)")
@EntityListeners(AuditingEntityListener.class)
@Data
public class CarApiModel {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    private UUID modelId;
    private String modelName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "make_id")
    private CarApiMake make;

    private LocalDateTime cacheTimestamp;

    public UUID getMakeId() {
        return make != null ? make.getId() : null;
    }
}
