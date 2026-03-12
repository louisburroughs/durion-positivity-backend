package com.positivity.mcp.internal.entity;

import com.positivity.mcp.internal.enums.NltiIntentStatus;
import com.positivity.mcp.internal.enums.NltiIntentType;
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
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "nlti_intent")
public class NltiIntent {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column
    private UUID requestId;

    @Column(nullable = false)
    private UUID sessionId;

    @Column(nullable = false)
    private UUID correlationId;

    @Enumerated(EnumType.STRING)
    @Column
    private NltiIntentType intentType;

    @Enumerated(EnumType.STRING)
    @Column
    private NltiIntentStatus status;

    @Enumerated(EnumType.STRING)
    @Column
    private NltiRiskLevel riskLevel;

    @Column(columnDefinition = "TEXT")
    private String slotsJson;

    @Column(columnDefinition = "TEXT")
    private String clarificationQuestionsJson;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private OffsetDateTime updatedAt;
}
