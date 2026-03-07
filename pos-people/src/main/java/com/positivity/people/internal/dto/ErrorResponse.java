package com.positivity.people.internal.dto;

import java.time.Instant;
import java.util.Map;

public class ErrorResponse {

	private String errorCode;

	private String message;

	private String correlationId;

	private Instant timestamp;

	private Map<String, String> fieldErrors;

	public ErrorResponse() {
	}

	public ErrorResponse(String errorCode, String message, String correlationId, Instant timestamp) {
		this.errorCode = errorCode;
		this.message = message;
		this.correlationId = correlationId;
		this.timestamp = timestamp;
	}

	public String getErrorCode() {
		return errorCode;
	}

	public void setErrorCode(String errorCode) {
		this.errorCode = errorCode;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public String getCorrelationId() {
		return correlationId;
	}

	public void setCorrelationId(String correlationId) {
		this.correlationId = correlationId;
	}

	public Instant getTimestamp() {
		return timestamp;
	}

	public void setTimestamp(Instant timestamp) {
		this.timestamp = timestamp;
	}

	public Map<String, String> getFieldErrors() {
		return fieldErrors;
	}

	public void setFieldErrors(Map<String, String> fieldErrors) {
		this.fieldErrors = fieldErrors;
	}

}
