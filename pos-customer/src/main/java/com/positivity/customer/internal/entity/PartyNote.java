package com.positivity.customer.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Local projection of party notes received from workorder events.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "party_note",
        indexes = {
            @Index(name = "idx_party_note_party_id", columnList = "party_id"),
            @Index(name = "idx_party_note_source_event", columnList = "source_event_id")
        })
public class PartyNote {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "note_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID noteId;

    @Column(name = "party_id", columnDefinition = "UUID", nullable = false)
    private UUID partyId;

    @Column(name = "note_text", length = 2000)
    private String noteText;

    @Column(name = "note_type", length = 100)
    private String noteType;

    @Column(name = "source_workorder_id", length = 64)
    private String sourceWorkorderId;

    @Column(name = "source_event_id", nullable = false, length = 64)
    private String sourceEventId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
