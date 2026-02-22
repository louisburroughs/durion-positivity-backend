package com.positivity.inventory.internal.entity;

import jakarta.persistence.*;

import com.positivity.inventory.internal.enums.TaskStatus;
import com.positivity.shared.id.UUIDv7Generator;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Represents a cycle count task assigned to an auditor for a specific bin and
 * item.
 * 
 * <p>
 * Based on requirements from issue #27, this entity tracks:
 * <ul>
 * <li>Assignment of count tasks to auditors</li>
 * <li>Expected quantity for blind counting</li>
 * <li>Current task status in the workflow</li>
 * <li>Reference to the latest count entry</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "cycle_count_task")
public class CycleCountTask {

    @Id
    @Column(name = "task_id", updatable = false, nullable = false)
    private UUID taskId;

    @PrePersist
    public void generateId() {
        if (taskId == null) {
            taskId = UUIDv7Generator.generate();
        }
    }

    /**
     * Storage bin location identifier.
     */
    @Column(name = "bin_location", nullable = false, length = 100)
    private String binLocation;

    /**
     * Item SKU to be counted.
     */
    @Column(name = "item_sku", nullable = false, length = 100)
    private String itemSku;

    /**
     * Item description for auditor reference.
     */
    @Column(name = "item_description", length = 500)
    private String itemDescription;

    /**
     * Expected quantity in the bin (system record).
     * This is NOT shown to the auditor during blind count.
     */
    @Column(name = "expected_quantity", nullable = false)
    private Integer expectedQuantity;

    /**
     * ID of the auditor assigned to this task.
     */
    @Column(name = "auditor_id", nullable = false, length = 100)
    private String auditorId;

    /**
     * Current status of the task in the workflow.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private TaskStatus status;

    /**
     * Reference to the latest count entry for this task.
     * Updated each time a count or recount is submitted.
     */
    @Column(name = "latest_count_entry_id")
    private UUID latestCountEntryId;

    /**
     * Number of count entries (original + recounts) for this task.
     * Used to enforce the maximum recount limit (3 total).
     */
    @Column(name = "count_entries_count", nullable = false)
    @Builder.Default
    private Integer countEntriesCount = 0;

    /**
     * Timestamp when the task was created.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * Timestamp of the last update to this task.
     */
    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
