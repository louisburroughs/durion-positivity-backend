package com.positivity.location.internal.entity;

import com.positivity.location.internal.enums.StorageLocationType;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Storage location aggregate for floor/shelf/bin/cage/truck topology.
 *
 * Issue: CAP-214 #39
 */
@Entity
@Table(name = "storage_location")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StorageLocationEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(unique = false)
    private String barcode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private StorageLocationType type;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(name = "site_id", nullable = false, columnDefinition = "UUID")
    private UUID siteId;

    @Column(name = "parent_storage_location_id", columnDefinition = "UUID")
    private UUID parentStorageLocationId;

    @Builder.Default
    @Column(name = "inventory_count", nullable = false)
    private int inventoryCount = 0;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
        if (status == null || status.isBlank()) {
            status = "ACTIVE";
        }
    }
}
