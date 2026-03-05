package com.positivity.accounting.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Envelope error response matching the contract
 * {@code {"error": {"code": "...", "message": "..."}}}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnvelopeErrorResponse {

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

    public static EnvelopeErrorResponse of(String code, String message) {
        return of(code, message, null);
    }

    public static EnvelopeErrorResponse of(String code, String message, Long timestamp) {
        return EnvelopeErrorResponse.builder()
                .error(Error.builder().code(code).message(message).timestamp(timestamp).build())
                .build();
    }
}
