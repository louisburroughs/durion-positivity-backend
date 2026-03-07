package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Response from creating or updating a time entry exception")
public class TimeEntryExceptionResponse {

	@Schema(description = "Unique identifier of the created or updated exception",
			example = "123e4567-e89b-12d3-a456-426614174000")
	private UUID exceptionId;

	@Schema(description = "Indicates whether the operation was successful", example = "true",
			requiredMode = Schema.RequiredMode.REQUIRED)
	private boolean success;

	@Schema(description = "Human-readable message describing the outcome", example = "Exception created successfully")
	private String message;

	public TimeEntryExceptionResponse() {
	}

	public TimeEntryExceptionResponse(UUID exceptionId, boolean success, String message) {
		this.exceptionId = exceptionId;
		this.success = success;
		this.message = message;
	}

	public UUID getExceptionId() {
		return exceptionId;
	}

	public void setExceptionId(UUID exceptionId) {
		this.exceptionId = exceptionId;
	}

	public boolean isSuccess() {
		return success;
	}

	public void setSuccess(boolean success) {
		this.success = success;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

}
