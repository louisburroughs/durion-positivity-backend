package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Marketing copy for a {@link TreadDesignEntity}, in one language (CAP-324 #1352).
 *
 * <p>Kept per language rather than flattened to a single description, mirroring
 * {@code SupplierCatalogEnrichmentText}: collapsing to one language at ingestion would force a
 * choice before any shop is known to read it. Replaced wholesale per design on every apply — the
 * event carries the design's full text set each time, so a language dropped by the vendor is a
 * language this table should stop holding, not one left stale.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "tread_design_text",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_tread_design_text_design_language",
                        columnNames = {"tread_design_id", "language_code"}))
public class TreadDesignTextEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tread_design_id", nullable = false, updatable = false)
    private UUID treadDesignId;

    @Column(name = "language_code", nullable = false, updatable = false, length = 16)
    private String languageCode;

    @Column(name = "name", length = 300)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "foot_notes", columnDefinition = "TEXT")
    private String footNotes;
}
