package com.positivity.warranty.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only typed location-parent edge replica carried on {@code location.location.updated}
 * facts (ADR-0044 §6), mirroring pos-people's, pos-inventory's and pos-workorder's replicas of the
 * same name. The owner allows exactly one parent per (child, parentType), so that pair is the
 * natural key; every location fact replaces the child's full edge set.
 *
 * <p>Feeds the materialised location-scope ancestor sets on {@link ExtLocationReplica}
 * (ADR-0061 §2, #1885): the upward walk follows these edges by type, and the downward walk finds
 * the descendants whose sets a re-parenting invalidates.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@IdClass(ExtLocationParentReplica.Key.class)
@Table(name = "ext_location_parent")
public class ExtLocationParentReplica {

    @Id
    @Column(name = "child_id", nullable = false)
    private UUID childId;

    @Id
    @Column(name = "parent_type", nullable = false, length = 64)
    private String parentType;

    @Column(name = "parent_id", nullable = false)
    private UUID parentId;

    /**
     * Explicit dependency hooks for the ArchUnit UUIDv7 rules (ADR-0013): the child/parent ids ARE
     * UUIDv7s minted by the owning module and stored verbatim. References both {@link UUIDv7Id}
     * (module ArchitectureTest) and {@link UUIDv7Generator} (cross-module EntityStandards) so both
     * identifier-standard rules recognise the replica, as {@code ExtVehicleReplica} does.
     */
    @Transient
    public Class<?>[] uuidv7Dependency() {
        return new Class<?>[] {UUIDv7Id.class, UUIDv7Generator.class};
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private UUID childId;
        private String parentType;
    }
}
