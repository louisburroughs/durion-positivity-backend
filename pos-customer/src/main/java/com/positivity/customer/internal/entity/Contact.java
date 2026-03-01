package com.positivity.customer.internal.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.positivity.customer.internal.enums.PreferredContactMethod;
import com.positivity.shared.id.UUIDv7Id;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
@Entity
@Table(name = "contact")
@EntityListeners(AuditingEntityListener.class)
@Schema(description = "Person that represents an organization-party.")
public class Contact {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "contact_id", columnDefinition = "UUID", updatable = false, nullable = false)
    @Schema(description = "Unique identifier of the contact")
    private UUID contactId;

    @Column(name = "person_id", nullable = false, columnDefinition = "UUID")
    @Schema(description = "Canonical person ID from pos-people")
    private UUID personId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "party_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private CommercialParty commercialParty;

    @NotBlank
    @Schema(description = "First name of the contact", example = "Jane")
    private String firstName;

    @NotBlank
    @Schema(description = "Last name of the contact", example = "Smith")
    private String lastName;

    @Email
    @Schema(description = "Email address of the contact", example = "jane.smith@example.com")
    private String email;

    @Schema(description = "Phone number of the contact", example = "+1-555-9999")
    private String phoneNumber;

    @Transient
    @Schema(description = "Preferred method of contact", example = "EMAIL")
    private PreferredContactMethod preferredContactMethod = PreferredContactMethod.NONE;

    @Column(nullable = false)
    @Schema(description = "Indicates if the contact is active for the party", example = "true")
    private boolean active = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Instant getModifiedAt() {
        return updatedAt;
    }

    public void setModifiedAt(Instant modifiedAt) {
        this.updatedAt = modifiedAt;
    }

    @Transient
    public UUID getId() {
        return contactId;
    }

    public void setId(UUID id) {
        this.contactId = id;
    }

    public void setPreferredContactMethod(PreferredContactMethod preferredContactMethod) {
        this.preferredContactMethod = preferredContactMethod == null ? PreferredContactMethod.NONE
                : preferredContactMethod;
    }
}
