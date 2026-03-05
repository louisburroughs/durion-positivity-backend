package com.positivity.people.internal.dto;

import java.time.Instant;
import java.util.UUID;

public class BreakDto {

	private UUID sessionId;

	private Instant startedAt;

	private Instant endedAt;

	public BreakDto() {
	}

	public BreakDto(UUID sessionId, Instant startedAt, Instant endedAt) {
		this.sessionId = sessionId;
		this.startedAt = startedAt;
		this.endedAt = endedAt;
	}

	public UUID getSessionId() {
		return sessionId;
	}

	public void setSessionId(UUID sessionId) {
		this.sessionId = sessionId;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public void setStartedAt(Instant startedAt) {
		this.startedAt = startedAt;
	}

	public Instant getEndedAt() {
		return endedAt;
	}

	public void setEndedAt(Instant endedAt) {
		this.endedAt = endedAt;
	}

}
