package com.positivity.people.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
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
 * facts (ADR-0044 §6), mirroring pos-inventory's replica of the same name. The owner allows
 * exactly one parent per (child, parentType), so that pair is the natural key; every location
 * fact replaces the child's full edge set.
 *
 * <p>Serves the hierarchy-root pick behind the top-level default-location fallback for
 * {@code GET /people/me/primary-location} (issue #1636).
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
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): the child/parent ids ARE
     * UUIDv7s minted by the owning module; this replica stores them verbatim.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private UUID childId;
        private String parentType;
    }
}
