package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.workorder.internal.enums.SubstituteType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "substitute_link")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class SubstituteLink {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID productId;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID substitutePartId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubstituteType substituteType;

    @Column(nullable = false)
    private boolean isAutoSuggest;

    @Column(nullable = false)
    private int priority;

    @Column
    private Instant effectiveFrom;

    @Column
    private Instant effectiveTo;

    @Column(nullable = false)
    private Boolean isActive;

    @Column(nullable = false)
    private String createdBy;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column
    private String updatedBy;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    public SubstituteLink(UUID id) {
        this.id = id;
    }
}
