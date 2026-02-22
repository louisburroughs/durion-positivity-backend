package com.positivity.customer.internal.entity;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.positivity.customer.internal.enums.PartyType;
import com.positivity.shared.id.UUIDv7Generator;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Commercial PartyEntity entity (organization/company) implementing Party
 * (CAP:091 Story #104).
 * Represents organizations doing business with the service provider.
 * Supports hierarchy and requires at least one contact.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Entity
@Table(name = "commercial_party")
@Schema(description = "Organization or company doing business with the service provider. Supports hierarchy and requires at least one contact.")
public class CommercialParty extends AbstractParty {

    @Column(unique = true, nullable = false)
    @NotBlank
    @Schema(description = "Unique party number", example = "PARTY-1001")
    private String partyNumber;

    @Column(nullable = false)
    @NotBlank
    @Schema(description = "Legal name of the organization", example = "Acme Corporation")
    private String legalName;

    @Schema(description = "Display name for the organization", example = "Acme")
    private String displayName;

    @Schema(description = "Tax identification number", example = "99-1234567")
    private String taxId;

    @Schema(description = "Billing terms foreign key", example = "NET30")
    private String billingTermsId;

    @Column(nullable = false)
    @Schema(description = "Party type (COMMERCIAL|PERSON)", example = "COMMERCIAL")
    private PartyType partyType = PartyType.COMMERCIAL;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_party_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private CommercialParty parentParty;

    @OneToMany(mappedBy = "parentParty", cascade = CascadeType.ALL)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Set<CommercialParty> childParties = new HashSet<>();

    @ElementCollection
    @CollectionTable(name = "party_external_identifier", joinColumns = @JoinColumn(name = "party_id"))
    @MapKeyColumn(name = "system_name")
    @Column(name = "identifier_value")
    @Schema(description = "External identifiers keyed by source system")
    private Map<String, String> externalIdentifiers = new HashMap<>();

    @Schema(description = "Primary address label or identifier for the organization", example = "123 Main St, Springfield")
    private String primaryAddress;

    @OneToMany(mappedBy = "commercialParty", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @NotEmpty
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Set<Contact> contacts = new HashSet<>();

    private void ensureContactsPresent() {
        if (contacts == null || contacts.isEmpty()) {
            throw new IllegalStateException("Party must have at least one contact");
        }
    }

    @PrePersist
    public void generateId() {
        if (getPartyId() == null) {
            setPartyId(UUIDv7Generator.generate());
        }
        validateNames();
        ensureContactsPresent();
        setCreatedAt(Instant.now());
        setModifiedAt(Instant.now());
    }

    @PreUpdate
    private void validateCustomer() {
        validateNames();
        ensureContactsPresent();
        setModifiedAt(Instant.now());
    }

    /** Helper method to validate customer names */
    protected void validateNames() {
        if (legalName == null || legalName.isBlank()) {
            throw new IllegalStateException("legalName is required for a customer");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalStateException("displayName is required for a customer");
        }
    }

    /**
     * Explicit dependency hook for ArchUnit UUIDv7 rule.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }

}
