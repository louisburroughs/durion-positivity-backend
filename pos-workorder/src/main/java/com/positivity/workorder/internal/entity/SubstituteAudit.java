package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "substitute_audit")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubstituteAudit {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID auditId;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID linkId;

    @Column(nullable = false)
    private String operation;

    @Column(columnDefinition = "TEXT")
    private String payloadBefore;

    @Column(columnDefinition = "TEXT")
    private String payloadAfter;

    @Column(nullable = false)
    private String actorId;

    @Column(nullable = false)
    private Instant timestamp;

    @Column
    private String correlationId;
}