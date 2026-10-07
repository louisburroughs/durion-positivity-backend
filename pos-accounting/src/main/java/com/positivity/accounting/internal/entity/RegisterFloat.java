package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The change float of one register (#2511; SPEC-accounting-workspace §4.6 "Float", AW16): a fixed
 * amount kept in its drawer and held on 1080 Register Float. The register is pos-order's {@code
 * terminalId} (AW31). Set and changed only by the go-live and Change float commands, or by the
 * reversal of their entries.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "register_float",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_register_float_register",
                    columnNames = {"tenant_id", "register_id"})
        })
public class RegisterFloat extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "register_float_id", nullable = false, columnDefinition = "UUID")
    private UUID registerFloatId;

    @Column(name = "register_id", length = 100, nullable = false, updatable = false)
    private String registerId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    /**
     * The ISO 4217 code the float is held in (#2577; ADR-0067 R-1, R-4): the ledger currency when the register's
     * first command created the row. A command in another currency is refused, so it never changes.
     */
    @Column(name = "currency_code", length = 3, nullable = false, updatable = false)
    private String currencyCode;

    /** The go-live entry while it stands; null before go-live and after its reversal. */
    @Column(name = "go_live_journal_entry_id")
    private UUID goLiveJournalEntryId;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "modified_at", nullable = false)
    private Instant updatedAt;
}
