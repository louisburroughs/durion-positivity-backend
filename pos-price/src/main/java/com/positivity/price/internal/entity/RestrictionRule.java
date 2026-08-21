package com.positivity.price.internal.entity;

import com.positivity.price.internal.enums.LocationTag;
import com.positivity.price.internal.enums.ServiceTag;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "restriction_rule")
public class RestrictionRule {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID ruleId;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LocationTag locationTag;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ServiceTag serviceTag;

    @Column(nullable = false)
    private LocalDate effectiveFrom;

    @Column
    private LocalDate effectiveTo;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false)
    @Builder.Default
    private boolean overrideable = false;

    @Column(nullable = false)
    @Builder.Default
    private Integer policyVersion = 1;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
