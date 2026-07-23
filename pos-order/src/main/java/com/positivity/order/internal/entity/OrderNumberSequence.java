package com.positivity.order.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Per-location, per-month sequence backing human-facing order numbers (plan story A2). Rows are
 * locked {@code FOR UPDATE} at assignment time; gapless numbering is explicitly NOT guaranteed.
 */
@Entity
@Table(name = "order_number_sequence")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderNumberSequence {

    @EmbeddedId
    private Key key;

    @Column(nullable = false)
    private long nextValue;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): this entity's natural key
     * is (locationId, period) — the locationId component IS a UUIDv7 issued by pos-location.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return com.positivity.shared.id.UUIDv7Generator.class;
    }

    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {

        @Column(nullable = false, columnDefinition = "UUID")
        private UUID locationId;

        /** Month bucket in {@code yyMM} form, e.g. {@code 2607}. */
        @Column(nullable = false, length = 4)
        private String period;
    }
}
