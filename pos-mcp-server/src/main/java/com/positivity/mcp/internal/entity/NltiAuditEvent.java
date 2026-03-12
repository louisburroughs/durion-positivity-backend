package com.positivity.mcp.internal.entity;

import com.positivity.mcp.internal.enums.NltiAuditEventType;
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
import org.hibernate.annotations.Immutable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Immutable
@Data
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "nlti_audit_event")
public class NltiAuditEvent {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false)
    private UUID correlationId;

    @Column
    private UUID sessionId;

    @Column
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NltiAuditEventType eventType;

    @Column(nullable = false)
    private OffsetDateTime timestamp;

    @Column
    private String actorSubjectId;

    @Column
    private String payloadRef;

    @Column
    private String payloadHash;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
