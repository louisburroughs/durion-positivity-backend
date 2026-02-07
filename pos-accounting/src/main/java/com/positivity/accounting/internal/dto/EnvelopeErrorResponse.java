package com.positivity.accounting.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

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
        @Builder.Default
        private Long timestamp = Instant.now().toEpochMilli();
    }

    public static EnvelopeErrorResponse of(String code, String message) {
        return EnvelopeErrorResponse.builder()
                .error(Error.builder().code(code).message(message).build())
                .build();
    }
}
