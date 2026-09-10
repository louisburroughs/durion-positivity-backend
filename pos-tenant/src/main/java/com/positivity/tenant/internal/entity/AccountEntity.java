package com.positivity.tenant.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import com.positivity.tenant.internal.enums.AccountStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The customer of Durion that owns one or more tenants (ADR-0062 §7): legal name, trading name,
 * status, tax id, home country and currency. Contacts and the billing profile hang off it. Every
 * row belongs to the platform tenant.
 */
@Entity
@Table(name = "account")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountEntity extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "legal_name", nullable = false, length = 200)
    private String legalName;

    @Column(name = "trading_name", length = 200)
    private String tradingName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "tax_id", length = 64)
    private String taxId;

    /** ISO 3166-1 alpha-2. */
    @Column(name = "home_country", nullable = false, length = 2)
    private String homeCountry;

    /** ISO 4217. */
    @Column(name = "home_currency", nullable = false, length = 3)
    private String homeCurrency;

    @Version
    @Column(nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
