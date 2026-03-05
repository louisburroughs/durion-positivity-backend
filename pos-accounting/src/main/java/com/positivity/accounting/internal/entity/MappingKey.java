package com.positivity.accounting.internal.entity;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.positivity.shared.id.UUIDv7Id;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Mapping Key - second level of GL mapping taxonomy hierarchy.
 * 
 * Each key belongs to a PostingCategory and can have multiple GLMappings.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - GL Mapping</a>
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "mapping_key", indexes = {
        @Index(name = "idx_mapping_key_category", columnList = "posting_category_id"),
        @Index(name = "idx_mapping_key_name", columnList = "key_name")
})
public class MappingKey {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "mapping_key_id", nullable = false, columnDefinition = "UUID")
    private UUID mappingKeyId;
    @Column(name = "posting_category_id", nullable = false)
    private UUID postingCategoryId;

    @Column(name = "key_name", length = 100, nullable = false)
    private String keyName;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    // Audit fields
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "modified_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "modified_by", length = 50, nullable = false)
    private String modifiedBy;
}
