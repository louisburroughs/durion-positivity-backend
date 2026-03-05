package com.positivity.people.internal.entity;

import com.positivity.people.internal.enums.AdjustmentStatus;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.*;
import java.time.Instant;

import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.util.UUID;

import com.positivity.shared.id.UUIDv7Id;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "time_entry_adjustment")
public class TimeEntryAdjustment {

	@Id
	@GeneratedValue
	@UUIDv7Id
	@Column(name = "adjustment_id", columnDefinition = "UUID", updatable = false, nullable = false)
	private UUID adjustmentId;

	@Column(name = "time_entry_id", nullable = false, columnDefinition = "UUID")
	private UUID timeEntryId;

	@Column(name = "reason_code", length = 200)
	private String reasonCode;

	@Column(name = "notes", columnDefinition = "TEXT")
	private String notes;

	@Column(name = "proposed_start_at")
	private Instant proposedStartAt;

	@Column(name = "proposed_end_at")
	private Instant proposedEndAt;

	@Column(name = "minutes_delta")
	private Integer minutesDelta;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 50)
	private AdjustmentStatus status;

	@Column(name = "created_by")
	private String createdBy;

	@CreatedDate
	@Column(name = "created_at")
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at")
	private Instant updatedAt;

	@Column(name = "decided_by")
	private String decidedBy;

	@Column(name = "decided_at")
	private Instant decidedAt;

	// Getters and setters
	public UUID getAdjustmentId() {
		return adjustmentId;
	}

	public void setAdjustmentId(UUID adjustmentId) {
		this.adjustmentId = adjustmentId;
	}

	public UUID getTimeEntryId() {
		return timeEntryId;
	}

	public void setTimeEntryId(UUID timeEntryId) {
		this.timeEntryId = timeEntryId;
	}

	public String getReasonCode() {
		return reasonCode;
	}

	public void setReasonCode(String reasonCode) {
		this.reasonCode = reasonCode;
	}

	public String getNotes() {
		return notes;
	}

	public void setNotes(String notes) {
		this.notes = notes;
	}

	public Instant getProposedStartAt() {
		return proposedStartAt;
	}

	public void setProposedStartAt(Instant proposedStartAt) {
		this.proposedStartAt = proposedStartAt;
	}

	public Instant getProposedEndAt() {
		return proposedEndAt;
	}

	public void setProposedEndAt(Instant proposedEndAt) {
		this.proposedEndAt = proposedEndAt;
	}

	public Integer getMinutesDelta() {
		return minutesDelta;
	}

	public void setMinutesDelta(Integer minutesDelta) {
		this.minutesDelta = minutesDelta;
	}

	public AdjustmentStatus getStatus() {
		return status;
	}

	public void setStatus(AdjustmentStatus status) {
		this.status = status;
	}

	public String getCreatedBy() {
		return createdBy;
	}

	public void setCreatedBy(String createdBy) {
		this.createdBy = createdBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
	}

	public String getDecidedBy() {
		return decidedBy;
	}

	public void setDecidedBy(String decidedBy) {
		this.decidedBy = decidedBy;
	}

	public Instant getDecidedAt() {
		return decidedAt;
	}

	public void setDecidedAt(Instant decidedAt) {
		this.decidedAt = decidedAt;
	}

}
