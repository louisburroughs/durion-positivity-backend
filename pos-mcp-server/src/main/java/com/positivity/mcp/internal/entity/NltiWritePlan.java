package com.positivity.mcp.internal.entity;

import com.positivity.mcp.internal.enums.NltiRequestStatus;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A previewed, awaiting-confirmation write action (Gate 6). An ACTION request produces a plan
 * (not an execution); the exact persisted args are what get executed on confirmation — never a
 * re-parse of the user's text.
 */
@Data
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "nlti_write_plan")
public class NltiWritePlan {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false)
    private UUID requestId;

    @Column(nullable = false)
    private UUID sessionId;

    /** Idempotency key — a confirmed-and-executed key is terminal; re-confirm returns the original. */
    @Column(nullable = false, unique = true, length = 128)
    private String idempotencyKey;

    @Column(nullable = false)
    private String targetTool;

    /** Exact arguments to execute (JSON). Executed verbatim on confirm; never re-parsed. */
    @Column(nullable = false, columnDefinition = "text")
    private String argsJson;

    /** Per-argument provenance (JSON: argName -> ArgProvenance). */
    @Column(nullable = false, columnDefinition = "text")
    private String argProvenanceJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NltiRiskLevel riskLevel;

    /** Source entity versions/ETags captured at plan time (JSON), for stale-data re-preview. */
    @Column(columnDefinition = "text")
    private String sourceEntityVersionsJson;

    /** Human-readable preview summary shown to the user. */
    @Column(nullable = false, columnDefinition = "text")
    private String summaryText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NltiRequestStatus status = NltiRequestStatus.PENDING_CONFIRMATION;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    private OffsetDateTime executedAt;
}
