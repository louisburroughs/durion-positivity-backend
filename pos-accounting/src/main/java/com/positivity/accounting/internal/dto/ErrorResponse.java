package com.positivity.accounting.internal.dto;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Standard error response format for the accounting domain.
 * 
 * Includes error code, message, and optional field-level validation errors.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Error Response</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    private String errorCode;
    private String message;
    private String correlationId;
    private Long timestamp;
    private Map<String, String> fieldErrors;
    private Error error;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Error {
        private String code;
        private String message;
        private Long timestamp;
    }

    public static ErrorResponse of(String code, String message) {
        return of(code, message, null);
    }

    /**
     * Builds a response that supports both formats:
     * <ul>
     * <li>Top-level: {@code errorCode}/{@code message}</li>
     * <li>Envelope: {@code error.code}/{@code error.message}</li>
     * </ul>
     */
    public static ErrorResponse of(String code, String message, Long timestamp) {
        return ErrorResponse.builder()
                .errorCode(code)
                .message(message)
                .timestamp(timestamp)
                .error(Error.builder()
                        .code(code)
                        .message(message)
                        .timestamp(timestamp)
                        .build())
                .build();
    }
}
