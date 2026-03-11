package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "link_id", nullable = false)
    @ToString.Exclude
    private SubstituteLink link;

    @jakarta.persistence.Transient
    public UUID getLinkId() {
        return link != null ? link.getId() : null;
    }

    @jakarta.persistence.Transient
    public void setLinkId(UUID linkId) {
        this.link = linkId != null ? new SubstituteLink(linkId) : null;
    }

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