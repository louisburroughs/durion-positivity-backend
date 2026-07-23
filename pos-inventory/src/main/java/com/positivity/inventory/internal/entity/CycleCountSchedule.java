package com.positivity.inventory.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Recurring cycle-count schedule (odoo-parity I1, issue #1031; Odoo's
 * {@code cyclic_inventory_frequency} / {@code next_inventory_date} analog).
 *
 * <p>A schedule describes how often a location (optionally narrowed to a
 * single zone and/or SKU category) should be counted. When
 * {@code nextDueDate} arrives, the scheduled runner either auto-creates the
 * next {@link CycleCountPlan} ({@code autoCreatePlan = true}) or simply
 * surfaces the schedule in the "due for count" view. Schedules are
 * deactivated, never hard-deleted.
 */
@Entity
@Table(name = "cycle_count_schedule")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class CycleCountSchedule {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "schedule_id", updatable = false, nullable = false)
    private UUID scheduleId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    /** Optional single-zone filter (v1); plans created from the schedule carry it as their only zone. */
    @Column(name = "zone_id")
    private UUID zoneId;

    /**
     * Optional SKU-category filter. Plain string in v1 — the catalog category
     * replica does not exist yet, so this is carried as metadata only.
     */
    @Column(name = "sku_category", length = 100)
    private String skuCategory;

    @Column(name = "frequency_days", nullable = false)
    private int frequencyDays;

    @Column(name = "next_due_date", nullable = false)
    private LocalDate nextDueDate;

    @Column(name = "auto_create_plan", nullable = false)
    private boolean autoCreatePlan;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
