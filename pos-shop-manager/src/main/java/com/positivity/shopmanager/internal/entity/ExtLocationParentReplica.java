package com.positivity.shopmanager.internal.entity;

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
 * Read-only typed location-parent edge replica carried on {@code location.location.updated} facts
 * (ADR-0044 §6, ADR-0061 §2, #1872), mirroring pos-people's and pos-inventory's replicas of the
 * same name. The owner allows exactly one parent per (child, parentType), so that pair is the
 * natural key; every location fact replaces the child's full edge set.
 *
 * <p>These edges are the adjacency the ancestor-set materialisation on {@link ExtLocationReplica}
 * walks; nothing else in this module reads them.
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

    /** ArchUnit UUIDv7 rule hook (ADR-0013): both ids are the owner's UUIDv7s, stored verbatim. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private UUID childId;
        private String parentType;
    }
}
