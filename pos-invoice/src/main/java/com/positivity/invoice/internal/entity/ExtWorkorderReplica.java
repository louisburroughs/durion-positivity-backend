package com.positivity.invoice.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Read-only workorder replica fed by {@code workorder.events.v1} (ADR-0044 §6, #897).
 *
 * <p>pos-workorder owns these facts; nothing in this module may write the table except the event
 * consumer. Serves invoice search's workorder-number translation and result-row enrichment,
 * previously answered by the retired {@code WorkorderReferenceClient}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "ext_workorder")
public class ExtWorkorderReplica {

    @Id
    @Column(name = "workorder_id", nullable = false)
    private UUID workorderId;

    @Column(name = "workorder_number")
    private String workorderNumber;

    @Column(name = "status", length = 64)
    private String status;

    /**
     * Owning customer party from the workorder fact (#921). Source for the scheduled
     * {@code invoices.customer_id} backfill patching pre-#920 invoices; null until a fact
     * carrying the customer arrives for this workorder.
     */
    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): the primary key IS a
     * UUIDv7 minted by the owning module's envelope factory; this replica stores it verbatim.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
