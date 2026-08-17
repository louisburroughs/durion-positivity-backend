package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One piece of artwork on a {@link TreadDesignEntity} (CAP-324 #1352).
 *
 * <p>{@code imageId} is a pos-image identifier — a reference, never fetched or re-stored here.
 * {@code unresolved} mirrors {@code SupplierCatalogEnrichmentImage}: a true value means the bytes
 * are still missing and pos-supplier's own retry runner will republish this design once they land,
 * not that the artwork does not exist. Replaced wholesale per design on every apply, same reasoning
 * as {@link TreadDesignTextEntity}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tread_design_image")
public class TreadDesignImageEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tread_design_id", nullable = false, updatable = false)
    private UUID treadDesignId;

    @Column(name = "image_type", length = 100)
    private String imageType;

    /** pos-image identifier for the stored bytes; absent when {@link #unresolved}. */
    @Column(name = "image_id")
    private Long imageId;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "source_uri", length = 1000)
    private String sourceUri;

    @Column(name = "unresolved", nullable = false)
    private boolean unresolved;
}
