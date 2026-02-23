package com.positivity.inventory.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.positivity.shared.id.UUIDv7Id;

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
    @GeneratedValue
    @UUIDv7Id
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

}
