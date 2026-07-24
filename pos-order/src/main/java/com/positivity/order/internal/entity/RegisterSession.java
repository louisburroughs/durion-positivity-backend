package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
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
 * A register (drawer) session on one terminal — the Odoo {@code pos.session} analog (parity stories
 * G1/G2, spec R6.1–R6.4). Orders tendered while the session is {@code OPEN} bind to it; closing
 * reconciles the counted drawer against the theoretical cash (opening float + Σ session CASH
 * settlements + Σ cash movements) and records the over/short.
 *
 * <p>The DB enforces one {@code OPEN} session per terminal via a partial unique index
 * ({@code ux_register_session_one_open_per_terminal}); closed sessions accumulate as history.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "register_session")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegisterSession {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "session_id", columnDefinition = "UUID")
    private UUID sessionId;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "terminal_id", nullable = false)
    private String terminalId;

    /** Shop location; supplies the location fallback for orders opened without an explicit one. */
    @Column(name = "location_id", columnDefinition = "UUID")
    private UUID locationId;

    @Column(name = "opened_by_clerk_id", nullable = false)
    private String openedByClerkId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RegisterSessionStatus status;

    /** Starting drawer cash; defaults from the terminal's previous counted close (R6.1). */
    @Column(name = "opening_float", nullable = false, precision = 19, scale = 4)
    private BigDecimal openingFloat;

    /** Physical cash counted at begin-close; null until then. */
    @Column(name = "counted_cash", precision = 19, scale = 4)
    private BigDecimal countedCash;

    /** Computed expected cash snapshotted at close for the Z-report and GL variance. */
    @Column(name = "theoretical_cash", precision = 19, scale = 4)
    private BigDecimal theoreticalCash;

    /** countedCash − theoreticalCash: positive is over, negative is short (null until closed). */
    @Column(name = "over_short", precision = 19, scale = 4)
    private BigDecimal overShort;

    @Column(name = "variance_approved", nullable = false)
    @Builder.Default
    private boolean varianceApproved = false;

    @Column(name = "variance_approved_by")
    private String varianceApprovedBy;

    @Column(name = "closed_by_clerk_id")
    private String closedByClerkId;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closing_started_at")
    private Instant closingStartedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
