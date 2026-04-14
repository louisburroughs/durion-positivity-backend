package com.positivity.nhtsa.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
public class VehicleVariableValue {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variable_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private VehicleVariable variable;

    public UUID getVariableId() {
        return variable != null ? variable.getId() : null;
    }

    private String value;
    private String valueId;
    private LocalDateTime cacheTimestamp;
}
