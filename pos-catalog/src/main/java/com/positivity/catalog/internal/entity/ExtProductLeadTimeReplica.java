package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only product lead-time replica fed by {@code inventory.events.v1} (ADR-0044 §6, #899).
 * pos-inventory owns these facts (derived from distributor/manufacturer feed ingest); nothing
 * in this module may write the table except the event consumer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_product_lead_time")
public class ExtProductLeadTimeReplica {

    @Id
    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "min_days")
    private Integer minDays;

    @Column(name = "max_days")
    private Integer maxDays;

    @Column(name = "display_text")
    private String displayText;

    @Column(name = "source", length = 64)
    private String source;

    @Column(name = "confidence", length = 32)
    private String confidence;

    @Column(name = "as_of")
    private Instant asOf;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): the primary key IS the
     * owning module's product id; this replica stores it verbatim.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
