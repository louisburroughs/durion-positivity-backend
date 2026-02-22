package com.positivity.inventory.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.positivity.shared.id.UUIDv7Generator;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks manufacturer part numbers that could not be mapped to a product.
 *
 * Issue: CAP-170 (#46)
 */
@Entity
@Table(name = "unmapped_manufacturer_part")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnmappedManufacturerPart {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String manufacturerId;

    @Column(nullable = false)
    private String manufacturerPartNumber;

    @Column(nullable = false)
    private Instant firstSeen;

    @Column(nullable = false)
    private Instant lastSeen;

    @Column(nullable = false)
    private Integer occurrenceCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UnmappedPartStatus status;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
    }
}
