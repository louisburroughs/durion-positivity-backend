package com.positivity.people.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
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

    // Derived, read-only: the legacy contact-info blob is no longer stored. It is computed
    // from person_contact_point (EMAIL / PHONE_WORK, primary + secondary) so existing readers
    // keep working without a duplicate column. JSON is assembled with portable string ops
    // (no DB-specific JSON functions) so it works on both Postgres and the H2 test DB. Values
    // are emails/phones (no embedded quotes), so manual JSON assembly is safe.
    @org.hibernate.annotations.Formula(
            "(select '{\"primaryEmail\":' || coalesce('\"' || max(case when cp.contact_type = 'EMAIL'"
                    + " and cp.is_primary then cp.\"value\" end) || '\"', 'null')"
                    + " || ',\"secondaryEmail\":' || coalesce('\"' || max(case when cp.contact_type = 'EMAIL'"
                    + " and not cp.is_primary then cp.\"value\" end) || '\"', 'null')"
                    + " || ',\"primaryPhone\":' || coalesce('\"' || max(case when cp.contact_type = 'PHONE_WORK'"
                    + " and cp.is_primary then cp.\"value\" end) || '\"', 'null')"
                    + " || ',\"secondaryPhone\":' || coalesce('\"' || max(case when cp.contact_type = 'PHONE_WORK'"
                    + " and not cp.is_primary then cp.\"value\" end) || '\"', 'null')"
                    + " || '}'"
                    + " from person_contact_point cp where cp.person_id = id having count(*) > 0)")
    private String contactInfoJson;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;
}
