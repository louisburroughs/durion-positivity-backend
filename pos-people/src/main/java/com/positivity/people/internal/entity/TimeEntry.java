package com.positivity.people.internal.entity;

import com.positivity.people.internal.enums.TimeEntryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.GeneratedValue;
import com.positivity.shared.id.UUIDv7Id;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "time_entry", indexes = {
		@Index(name = "idx_time_entry_attendance_window", columnList = "attendance_start_at, attendance_end_at"),
		@Index(name = "idx_time_entry_location_start", columnList = "location_id, attendance_start_at"),
		@Index(name = "idx_time_entry_person_start", columnList = "person_id, attendance_start_at") })
public class TimeEntry {

	@Id
	@GeneratedValue
	@UUIDv7Id
	@Column(name = "time_entry_id", columnDefinition = "UUID", nullable = false)
	private UUID timeEntryId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "person_id", referencedColumnName = "id", columnDefinition = "UUID", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	private Person person;

	/**
	 * Optional association to timesheet (if this time entry was created via
	 * timesheet import)
	 * NOTE: this is not a foreign key association since timesheet records are
	 * managed by an external timekeeping system; this is just a reference field for
	 * traceability.
	 */
	@Column(name = "timesheet_id")
	private String timesheetId;

	@Column(name = "location_id")
	private UUID locationId;

	@Column(name = "attendance_start_at")
	private Instant attendanceStartAt;

	@Column(name = "attendance_end_at")
	private Instant attendanceEndAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "status")
	private TimeEntryStatus status;

	@Column(name = "approved_by")
	private String approvedBy;

	@Column(name = "approved_at")
	private Instant approvedAt;

	@Column(name = "rejected_by")
	private String rejectedBy;

	@Column(name = "rejected_at")
	private Instant rejectedAt;

	@Column(name = "rejection_reason", length = 500)
	private String rejectionReason;

	@CreatedDate
	@Column(name = "created_at", updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at")
	private Instant updatedAt;

}
