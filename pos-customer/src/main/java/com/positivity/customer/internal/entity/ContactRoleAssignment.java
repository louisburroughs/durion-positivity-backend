package com.positivity.customer.internal.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import com.positivity.shared.id.UUIDv7Id;
/**
 * Contact role assignment entity linking contacts to customer accounts with
 * specific roles.
 * 
 * <p>
 * Uses a composite primary key consisting of contactId, customerAccountId, and
 * roleName.
 * This design supports the following requirements from issue #108:
 * </p>
 * <ul>
 * <li>A contact can have multiple roles within a single account</li>
 * <li>Each role can be marked as primary or non-primary</li>
 * <li>Only one contact can be primary for a given role per account</li>
 * </ul>
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/108">Backend
 *      Issue #108</a>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "contact_role_assignment", uniqueConstraints = {
        @UniqueConstraint(name = "uk_primary_role_per_account", columnNames = { "customer_account_id", "role_name",
                "is_primary" })
}, indexes = {
        @Index(name = "idx_contact_role_contact", columnList = "contact_id"),
        @Index(name = "idx_contact_role_account", columnList = "customer_account_id"),
        @Index(name = "idx_contact_role_role", columnList = "role_name")
})
@IdClass(ContactRoleAssignment.ContactRoleAssignmentId.class)
@EntityListeners(AuditingEntityListener.class)
@Schema(description = "Assignment of a role to a contact within a customer account")
public class ContactRoleAssignment {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "contact_id", nullable = false)
    @Schema(description = "Contact (Person) UUID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID contactId;

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "customer_account_id", nullable = false)
    @Schema(description = "Customer account UUID", example = "660e8400-e29b-41d4-a716-446655440000")
    private UUID customerAccountId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "role_name", nullable = false, length = 50)
    @Schema(description = "Role assigned to the contact", example = "BILLING")
    private ContactRole roleName;

    @Builder.Default
    @Column(name = "is_primary", nullable = false)
    @Schema(description = "Whether this is the primary contact for this role", example = "true")
    private boolean primary = false;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    @Schema(description = "Timestamp when the role assignment was created")
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    @Schema(description = "Timestamp when the role assignment was last updated")
    private Instant updatedAt;

    /**
     * Composite key class for ContactRoleAssignment.
     * Required by JPA for entities with composite primary keys using @IdClass.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContactRoleAssignmentId implements Serializable {
        private UUID contactId;
        private UUID customerAccountId;
        private ContactRole roleName;
    }

    /**
     * Explicit dependency hook for ArchUnit UUIDv7 rule.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return com.positivity.shared.id.UUIDv7Generator.class;
    }
}
