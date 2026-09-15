package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.EntryMethod;
import com.positivity.inventory.internal.enums.ReceivingSessionStatus;
import com.positivity.inventory.internal.enums.SourceDocumentType;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "receiving_session")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class ReceivingSession extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID sessionId;

    @Column(nullable = false, length = 255)
    private String sourceDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private SourceDocumentType sourceDocumentType;

    /**
     * Site this session receives at, captured from the source document's ship-to when the session
     * opened (#2009). Fixed at creation on purpose: the source order's ship-to can be revised
     * mid-session, and a session's stock does not move site when it is. Null for sessions opened
     * before the column existed, which fall back to reading the projection.
     */
    @Column(name = "site_id")
    private UUID siteId;

    @Column(length = 255)
    private String supplierId;

    @Column(length = 255)
    private String shipmentReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ReceivingSessionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private EntryMethod entryMethod;

    @Column(nullable = false, length = 255)
    private String createdByUserId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ReceivingLine> lines = new ArrayList<>();
}
