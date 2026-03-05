package com.positivity.people.internal.client;

public class WorkexecClientException extends RuntimeException {

	private final int httpStatus;

	private final String errorCode;

	public WorkexecClientException(String message, int httpStatus, String errorCode) {
		super(message);
		this.httpStatus = httpStatus;
		this.errorCode = errorCode;
	}

	public WorkexecClientException(String message, int httpStatus, String errorCode, Throwable cause) {
		super(message, cause);
		this.httpStatus = httpStatus;
		this.errorCode = errorCode;
	}

	public int getHttpStatus() {
		return httpStatus;
	}

	public String getErrorCode() {
		return errorCode;
	}

}
