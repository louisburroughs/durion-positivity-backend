package com.positivity.inboundmail.internal.entity;

import com.positivity.inboundmail.internal.enums.AddressStatus;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantGlobal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One intake address of one tenant (S27 ruling 8). The address text is an unguessable capability, so it
 * is kept out of {@link #toString()}: it is never logged, never copied to Kafka, and referred to
 * everywhere else by {@link #addressId}.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@TenantGlobal(
        reason = "address registry: the receive path resolves a recipient to its tenant before any tenant is bound;"
                + " tenant_id is carried as data (db/tenancy-global-tables.txt)")
@Table(name = "inbound_address")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "address")
public class InboundAddress {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "address_id", columnDefinition = "UUID")
    private UUID addressId;

    /** Lower-case {@code bills-<26 base32>@domain}; unique across the platform. */
    @Column(name = "address", nullable = false, updatable = false, length = 128)
    private String address;

    /** The owning tenant, plain data on this global table. */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 8)
    private AddressStatus status;

    /** The rotation request that issued this address; {@code null} for one issued by {@code POST /addresses}. */
    @Column(name = "issued_by_request_id", updatable = false)
    private UUID issuedByRequestId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "retired_at")
    private Instant retiredAt;
}
