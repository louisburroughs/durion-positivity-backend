package com.positivity.customer.internal.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Data
@NoArgsConstructor
@Entity
@Table(name = "party")
@Schema(description = "Organization or company doing business with the service provider. Supports hierarchy and requires at least one contact.")
public class Party {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Schema(description = "Unique identifier of the party", nullable = false)
    private UUID id;

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
    @Schema(description = "Party type (ORGANIZATION|INDIVIDUAL)", example = "ORGANIZATION")
    private String partyType = "ORGANIZATION";

    @Column(nullable = false)
    @Schema(description = "Status of the party", example = "ACTIVE")
    private String status = "ACTIVE";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_party_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Party parentParty;

    @OneToMany(mappedBy = "parentParty", cascade = CascadeType.ALL)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Set<Party> childParties = new HashSet<>();

    @ElementCollection
    @CollectionTable(name = "party_vehicle", joinColumns = @JoinColumn(name = "party_id"))
    @Column(name = "vin")
    @Schema(description = "VINs associated with this organization")
    private Set<String> vehicleVins = new HashSet<>();

    @ElementCollection
    @CollectionTable(name = "party_external_identifier", joinColumns = @JoinColumn(name = "party_id"))
    @MapKeyColumn(name = "system_name")
    @Column(name = "identifier_value")
    @Schema(description = "External identifiers keyed by source system")
    private Map<String, String> externalIdentifiers = new HashMap<>();

    @Schema(description = "Primary address label or identifier for the organization", example = "123 Main St, Springfield")
    private String primaryAddress;

    @OneToMany(mappedBy = "party", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @NotEmpty
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Set<Contact> contacts = new HashSet<>();

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant modifiedAt;

    @PrePersist
    @PreUpdate
    private void ensureContactsPresent() {
        if (contacts == null || contacts.isEmpty()) {
            throw new IllegalStateException("Party must have at least one contact");
        }
    }
}
