package com.positivity.people.internal.entity;

import com.positivity.people.internal.enums.ApprovalStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.GeneratedValue;
import com.positivity.shared.id.UUIDv7Id;

@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "timekeeping_entry", uniqueConstraints = {
		@UniqueConstraint(columnNames = { "tenant_id", "source_system", "source_session_id" }) })
public class TimekeepingEntry {

	@Id
	@GeneratedValue
	@UUIDv7Id
	@Column(name = "timekeeping_entry_id", columnDefinition = "UUID", nullable = false, updatable = false)
	private UUID timekeepingEntryId;

	@Column(name = "tenant_id", columnDefinition = "UUID", nullable = false)
	private UUID tenantId;

	@Column(name = "source_system", nullable = false, length = 50)
	private String sourceSystem = "shopmgr";

	@Column(name = "source_session_id", columnDefinition = "UUID", nullable = false)
	private UUID sourceSessionId;

	@Column(name = "original_source_session_id", nullable = true)
	private UUID originalSourceSessionId;

	@Column(name = "correction_id", nullable = true)
	private UUID correctionId;

	@Column(name = "correction_reason", nullable = true, columnDefinition = "TEXT")
	private String correctionReason;

	@Column(name = "employee_id", columnDefinition = "UUID", nullable = false)
	private UUID employeeId;

	@Column(name = "session_start_time", nullable = false)
	private Instant sessionStartTime;

	@Column(name = "session_end_time", nullable = false)
	private Instant sessionEndTime;

	@Enumerated(EnumType.STRING)
	@Column(name = "approval_status", nullable = false, length = 50)
	private ApprovalStatus approvalStatus = ApprovalStatus.PENDING_APPROVAL;

	@Column(name = "associated_work_order_id", columnDefinition = "UUID")
	private UUID associatedWorkOrderId;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

}