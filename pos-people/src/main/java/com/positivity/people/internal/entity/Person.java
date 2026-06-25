package com.positivity.people.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
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
 * Person identity. Employment attributes (employee number, status, hire/termination dates)
 * live on {@link Employee} ({@code employee.person_id}). Email + phone consolidated into
 * person_contact_point (EMAIL / PHONE_WORK); username is owned by pos-security and resolved
 * via user_person_link.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Person {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    private String firstName;

    private String lastName;

    @Column(name = "legal_name")
    private String legalName;

    @Column(name = "preferred_name")
    private String preferredName;

    @Lob
    @Column(name = "contact_info_json")
    private String contactInfoJson;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;
}
