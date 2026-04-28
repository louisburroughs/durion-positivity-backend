package com.positivity.customer.internal.entity;

import com.positivity.customer.internal.enums.AccountStatus;
import com.positivity.customer.internal.enums.AccountTier;
import com.positivity.shared.id.UUIDv7Id;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.Email;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Abstract base class for individual customers implementing PartyEntity
 * (CAP:091 Story #104).
 * Customers are parties that can own and manage vehicles.
 */
@Data
@NoArgsConstructor
@Entity
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
@EntityListeners(AuditingEntityListener.class)
@Schema(description = "Abstract base class for an individual customer (person). Use CommercialParty for organizations.")
public abstract class AbstractParty implements Party {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "customer_id", columnDefinition = "UUID", updatable = false, nullable = false)
    private UUID partyId;

    @Column(unique = true, nullable = false)
    @Schema(description = "Unique customer number", example = "CUST-1001")
    private String customerNumber;

    @Schema(description = "Phone number of the customer", example = "+1-888-555-1234")
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

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @LastModifiedBy
    @Column(name = "modified_by")
    private String modifiedBy;

    /** Helper method to validate customer names */
    protected abstract void validateNames();

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

    /**
     * Explicit dependency hook for ArchUnit UUIDv7 rule.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return com.positivity.shared.id.UUIDv7Generator.class;
    }

    /**
     * Backward-compatible accessor for legacy code paths that still reference
     * modifiedAt.
     */
    public Instant getModifiedAt() {
        return updatedAt;
    }

    public void setModifiedAt(Instant modifiedAt) {}
}
