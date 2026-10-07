package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.UndepositedSessionStatus;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * A closed register session's drawer cash on its way to the bank (CAP:550 S18, #2514; SPEC-accounting-workspace §4.5,
 * §7.1 "Undeposited sessions"; AW10). Written once from the session's close fact (schema version 2, functional
 * currency) in the transaction that posts its drawer movements and over/short; the amounts never change afterwards.
 * Only the status and the deposit move: a deposit takes the session whole, and reversing that deposit returns it.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "undeposited_session",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_undeposited_session_session",
                    columnNames = {"tenant_id", "session_id"})
        })
public class UndepositedSession extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "undeposited_session_id", nullable = false, columnDefinition = "UUID")
    private UUID undepositedSessionId;

    /** pos-order's register session. */
    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    /** The register (pos-order's {@code terminalId}, AW31). */
    @Column(name = "terminal_id", length = 100, nullable = false, updatable = false)
    private String terminalId;

    /** The session's location; null when the session carried none. */
    @Column(name = "location_id", updatable = false)
    private UUID locationId;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "closed_at", nullable = false, updatable = false)
    private Instant closedAt;

    @Column(name = "opening_float", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal openingFloat;

    @Column(name = "counted_cash", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal countedCash;

    @Column(name = "theoretical_cash", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal theoreticalCash;

    /** Counted minus theoretical: positive over, negative short. */
    @Column(name = "over_short", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal overShort;

    /** The session's CASH tender total: what its sales put in 1090 Undeposited Funds. */
    @Column(name = "expected_cash", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal expectedCash;

    /**
     * The signed sum of the 1095 Register Cash Clearing lines the session's over/short and drawer movement entries
     * posted, debit positive: a short or a petty expense makes it negative (a credit), an over positive.
     */
    @Column(name = "clearing_net", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal clearingNet;

    /** The total of the session's bank drops: what the bank receives for it. */
    @Column(name = "deposit_amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal depositAmount;

    /** The ISO 4217 code of every amount (ADR-0067 R-1): the functional currency, else no row is written. */
    @Column(name = "currency_code", length = 3, nullable = false, updatable = false)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private UndepositedSessionStatus status;

    /** The standing deposit that took the session; null while it is UNDEPOSITED. */
    @Column(name = "deposit_id")
    private UUID depositId;

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
