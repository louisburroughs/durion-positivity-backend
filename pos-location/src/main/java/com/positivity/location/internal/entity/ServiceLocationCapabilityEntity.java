package com.positivity.location.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.util.Locale;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "service_location_capabilities")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServiceLocationCapabilityEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @PrePersist
    void onCreate() {
        normalizeCode();
    }

    @PreUpdate
    void onUpdate() {
        normalizeCode();
    }

    private void normalizeCode() {
        if (code != null) {
            code = code.trim().toUpperCase(Locale.ROOT);
        }
    }
}
