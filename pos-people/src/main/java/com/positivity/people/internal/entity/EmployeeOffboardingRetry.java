package com.positivity.people.internal.entity;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.positivity.people.internal.enums.AssignmentTerminationPolicy;
import com.positivity.shared.id.UUIDv7Id;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "employee_offboarding_retry_queue")
@Getter
@Setter
public class EmployeeOffboardingRetry {

	@Id
	@GeneratedValue
	@UUIDv7Id
	@Column(columnDefinition = "UUID")
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "employee_id", nullable = false)
	private Person employee;

	public UUID getEmployeeId() {
		return employee != null ? employee.getId() : null;
	}

	@Enumerated(EnumType.STRING)
	@Column(name = "assignment_policy", nullable = false)
	private AssignmentTerminationPolicy assignmentPolicy;

	@Column(name = "disable_reason")
	private String disableReason;

	@Column(name = "actor_id", nullable = false)
	private String actorId;

	@Column(name = "failure_reason", nullable = false)
	private String failureReason;

	@Column(name = "attempts", nullable = false)
	private int attempts;

	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;

	@CreatedDate
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at")
	private Instant updatedAt;

}
