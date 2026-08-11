package com.positivity.peoplecontact.internal.entity;

import com.positivity.peoplecontact.internal.enums.PartyType;
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
import jakarta.persistence.UniqueConstraint;
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
 * Structured, international postal address for a person or organization party (FI-4, #1135).
 * pos-people-contact is the postal-address authority for both party kinds (ADR-0015); one
 * primary address per {@code (partyType, partyId)}. {@code region} is a free-text administrative
 * area and {@code postalCode} is nullable — several countries do not use one — so the model
 * carries no country-specific assumptions beyond the ISO 3166-1 alpha-2 {@code countryCode}.
 */
@Entity
@Table(
        name = "party_postal_address",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_party_postal_address_party",
                        columnNames = {"party_type", "party_id"}),
        indexes = @Index(name = "idx_party_postal_address_party", columnList = "party_id"))
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartyPostalAddress {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", columnDefinition = "UUID", updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "party_type", nullable = false, length = 20)
    private PartyType partyType;

    /** person.id for PERSON rows; the external organization party UUID for ORGANIZATION rows. */
    @Column(name = "party_id", nullable = false)
    private UUID partyId;

    @Column(name = "line1", nullable = false, length = 255)
    private String line1;

    @Column(name = "line2", length = 255)
    private String line2;

    @Column(name = "city", length = 120)
    private String city;

    @Column(name = "region", length = 120)
    private String region;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;
}
