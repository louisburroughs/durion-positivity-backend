package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The tenant's registered audit export webhook destination, without its signing secret (#2497). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "The tenant's audit export webhook destination; the signing secret is never returned here")
public class AuditExportWebhookResponse {

    @Schema(
            description = "Where WEBHOOK audit exports are delivered",
            example = "https://siem.example.com/hooks/positivity-audit",
            requiredMode = REQUIRED)
    private String url;

    @Schema(
            description = "When the destination was last registered (the signing secret was rotated then)",
            example = "2026-10-12T09:30:00Z",
            requiredMode = REQUIRED)
    private Instant registeredAt;

    @Schema(description = "Username that last registered the destination", example = "admin", requiredMode = REQUIRED)
    private String registeredBy;
}
