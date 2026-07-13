package com.positivity.securityservice.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only CRM customer-standing replica fed by {@code customer.events.v1}
 * ({@code customer.person-identity.updated}, ADR-0044 §6, #891). pos-customer owns the flags;
 * self-registration joins them with the {@code ext_people_contact_person} identity replica by
 * {@code personId} to assess CRM conflict signals — the surface previously fetched synchronously
 * by {@code CustomerRegistrationClient}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_customer_person_identity")
public class ExtCustomerPersonIdentity {

    @Id
    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @Column(name = "person_party_id")
    private UUID personPartyId;

    @Column(name = "individual_customer", nullable = false)
    private boolean individualCustomer;

    @Column(name = "commercial_contact", nullable = false)
    private boolean commercialContact;

    @Column(name = "commercial_account_count", nullable = false)
    private int commercialAccountCount;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** ArchUnit UUIDv7 rule hook (ADR-0013): the key is the owner's UUIDv7, stored verbatim. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
