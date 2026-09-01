package com.positivity.workorder.internal.entity;

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
import org.hibernate.annotations.Immutable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A note about the customer, recorded while a workorder is being worked (issue #1584).
 *
 * <p>This is the workorder's own record — what the advisor or technician wrote, on which job, and
 * when. pos-customer keeps a separate projection of the same note on the party timeline, fed by
 * {@code workorder.note.added.v1}; this table stays the source of truth for it.
 *
 * <p>Distinct from the note fields already on {@link Workorder} and {@link ChangeRequest}
 * ({@code completionNotes}, {@code approvalNotes}, {@code approvalNote}): those describe the work
 * or a decision about it, are single-valued, and are not about the customer.
 */
@Entity
// Append-only: a note is what someone wrote at a moment, so there is nothing to update and no
// updatedAt column (ADR-0024 §1, category 1). CRM's projection is downstream of this row.
@Immutable
@Table(
        name = "workorder_note",
        indexes = {@Index(name = "idx_workorder_note_workorder", columnList = "workorder_id, created_at")})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class WorkorderNote {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "note_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID noteId;

    @Column(name = "workorder_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID workorderId;

    /** Caller-supplied classification, e.g. {@code CUSTOMER_REQUEST}. Free text, not an enum: the
     * shops' vocabularies differ and CRM only displays it. */
    @Column(name = "note_type", length = 100)
    private String noteType;

    @Column(name = "note_text", length = 2000, nullable = false)
    private String noteText;

    /** The user who wrote the note, from the gateway-populated security context. */
    @Column(name = "authored_by", length = 255)
    private String authoredBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
