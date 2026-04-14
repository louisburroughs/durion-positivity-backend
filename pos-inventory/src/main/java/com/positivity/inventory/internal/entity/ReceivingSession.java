package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.EntryMethod;
import com.positivity.inventory.internal.enums.ReceivingSessionStatus;
import com.positivity.inventory.internal.enums.SourceDocumentType;
import com.positivity.shared.id.UUIDv7Id;
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
public class ReceivingSession {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID sessionId;

    @Column(nullable = false, length = 255)
    private String sourceDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private SourceDocumentType sourceDocumentType;

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
