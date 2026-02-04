package com.positivity.customer.internal.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.positivity.customer.internal.enums.AccountStatus;
import com.positivity.customer.internal.enums.AccountTier;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Abstract base class for individual customers implementing PartyEntity
 * (CAP:091 Story #104).
 * Customers are parties that can own and manage vehicles.
 */
@Data
@NoArgsConstructor
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "customer_type")
@Schema(description = "Abstract base class for an individual customer (person). Use CommercialParty for organizations.")
public abstract class AbstractParty implements Party {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "customer_id", updatable = false, nullable = false)
    private UUID partyId;

    @Column(unique = true, nullable = false)
    @Schema(description = "Unique customer number", example = "CUST-1001")
    private String customerNumber;

    @NotBlank
    @Schema(description = "Last name of the customer", example = "Doe")
    private String lastName;

    @NotBlank
    @Schema(description = "First name of the customer", example = "John")
    private String firstName;

    @Schema(description = "Phone number of the customer", example = "+1-555-1234")
    private String phoneNumber;

    @Email
    @Schema(description = "Email address of the customer", example = "john.doe@example.com")
    private String email;

    @Schema(description = "Primary address label or identifier for the customer", example = "123 Main St, Springfield")
    private String primaryAddress;

    @ElementCollection
    @CollectionTable(name = "customer_vehicle", joinColumns = @JoinColumn(name = "customer_id"))
    @Column(name = "vin")
    @Schema(description = "Set of vehicle VINs associated with the customer")
    private Set<String> vehicleVins = new HashSet<>();

    @Column(nullable = false)
    @Schema(description = "Status of the customer", example = "ACTIVE")
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(nullable = false)
    @Schema(description = "Account tier level", example = "STANDARD")
    private AccountTier tier = AccountTier.STANDARD;

    @Schema(description = "When the tier was last assigned or updated")
    private Instant tierAssignedAt;

    @Schema(description = "User who assigned or last modified the tier")
    private String tierAssignedBy;

    @Schema(description = "Whether tier was manually assigned (true) or auto-calculated (false)")
    private boolean tierManualOverride = false;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant modifiedAt;

    @PrePersist
    @PreUpdate
    private void validateCustomer() {
        if (firstName == null || firstName.isBlank()) {
            throw new IllegalStateException("firstName is required for a customer");
        }
        if (lastName == null || lastName.isBlank()) {
            throw new IllegalStateException("lastName is required for a customer");
        }
    }

    @Override
    public void addVehicleVin(String vin) {
        if (vehicleVins == null) {
            vehicleVins = new HashSet<>();
        }
        vehicleVins.add(vin);
    }

    @Override
    public void removeVehicleVin(String vin) {
        if (vehicleVins != null) {
            vehicleVins.remove(vin);
        }
    }
}
