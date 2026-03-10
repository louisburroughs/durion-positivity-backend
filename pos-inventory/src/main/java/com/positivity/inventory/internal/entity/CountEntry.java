package com.positivity.inventory.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Immutable record of a physical count performed by an auditor.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "count_entry")
@EntityListeners(AuditingEntityListener.class)
public class CountEntry {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "count_entry_id", updatable = false, nullable = false)
    private UUID countEntryId;

    @ManyToOne(optional = false)
    @JoinColumn(name = "cycle_count_task_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private CycleCountTask cycleCountTask;

    @Column(name = "auditor_id", nullable = false, length = 100)
    private String auditorId;

    @Column(name = "actual_quantity", nullable = false)
    private Integer actualQuantity;

    @Column(name = "expected_quantity", nullable = false)
    private Integer expectedQuantity;

    @Column(name = "variance", nullable = false)
    private Integer variance;

    @Column(name = "recount_sequence_number", nullable = false)
    @Builder.Default
    private Integer recountSequenceNumber = 0;

    @Column(name = "recount_of_count_entry_id")
    private UUID recountOfCountEntryId;

    @Column(name = "counted_at", nullable = false, updatable = false)
    private Instant countedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    @Transient
    public boolean isRecount() {
        return recountSequenceNumber != null && recountSequenceNumber > 0;
    }
}
