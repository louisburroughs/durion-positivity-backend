package com.positivity.people.internal.entity;

import com.positivity.people.internal.enums.ContactPointType;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Typed contact point (email, phone) owned by a {@link Person}. pos-people is the
 * source of truth for person contact data (ADR-0015 I2); consolidates the contact
 * taxonomy previously held only in pos-customer.contact_point.
 */
@Entity
@Table(
        name = "person_contact_point",
        indexes = {
            @Index(name = "idx_person_contact_point_person", columnList = "person_id"),
            @Index(name = "idx_person_contact_point_type", columnList = "contact_type")
        })
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PersonContactPoint {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", columnDefinition = "UUID", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @Enumerated(EnumType.STRING)
    @Column(name = "contact_type", nullable = false, length = 20)
    private ContactPointType contactType;

    // `value` is a reserved word in H2 (and some dialects); backtick-quote so Hibernate
    // emits a dialect-quoted identifier that maps to the existing lowercase `value` column.
    @Column(name = "`value`", nullable = false, length = 255)
    private String value;

    @Column(name = "is_primary", nullable = false)
    private boolean isPrimary;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;
}
