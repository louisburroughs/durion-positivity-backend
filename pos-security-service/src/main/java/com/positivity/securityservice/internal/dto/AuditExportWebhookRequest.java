package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for PUT /v1/audit/exports/webhook (#2497). The URL is validated by the service, not by
 * bean validation, so every problem with it answers the same 400 {@code VALIDATION_ERROR} with a
 * {@code fieldErrors} entry on {@code url}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "The endpoint the tenant's WEBHOOK audit exports are delivered to")
public class AuditExportWebhookRequest {

    @Schema(
            description = "Absolute https URL with a DNS host name, no user info and no fragment, at most 2048"
                    + " characters; the host must resolve only to publicly routable addresses",
            example = "https://siem.example.com/hooks/positivity-audit",
            maxLength = 2048,
            requiredMode = REQUIRED)
    private String url;
}
