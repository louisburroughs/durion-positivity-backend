package com.positivity.customer.internal.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.positivity.customer.internal.dto.PreferredContactMethod;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
@Entity
@Table(name = "contact")
@Schema(description = "Person that represents an organization-party.")
public class Contact {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "contact_id", updatable = false, nullable = false)
    @Schema(description = "Unique identifier of the contact")
    private UUID id;

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

    @Column(nullable = false)
    @Schema(description = "Indicates if the contact is active for the party", example = "true")
    private boolean active = true;

    @CreationTimestamp
    @Column(updatable = false)
    private java.time.Instant createdAt;

    @UpdateTimestamp
    private java.time.Instant modifiedAt;

    public void setPreferredContactMethod(PreferredContactMethod email2) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'setPreferredContactMethod'");
    }
}
