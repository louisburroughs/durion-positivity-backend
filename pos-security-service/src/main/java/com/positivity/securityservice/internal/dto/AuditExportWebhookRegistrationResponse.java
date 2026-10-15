package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * The answer to registering the tenant's audit export webhook (#2497): the destination plus its new
 * signing secret, which this response is the only place ever to carry. {@code toString} leaves the
 * secret out.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "The registered audit export webhook destination and its signing secret, shown only once")
public class AuditExportWebhookRegistrationResponse {

    @Schema(
            description = "Where WEBHOOK audit exports are delivered",
            example = "https://siem.example.com/hooks/positivity-audit",
            requiredMode = REQUIRED)
    private String url;

    @Schema(
            description = "When the destination was registered",
            example = "2026-10-12T09:30:00Z",
            requiredMode = REQUIRED)
    private Instant registeredAt;

    @Schema(description = "Username that registered the destination", example = "admin", requiredMode = REQUIRED)
    private String registeredBy;

    @ToString.Exclude
    @Schema(
            description = "CONFIDENTIAL. New HMAC-SHA256 signing secret (32 random bytes, base64url without padding);"
                    + " returned only in this response and never again, and every earlier secret stops working."
                    + " Each delivery carries X-Audit-Export-Signature: sha256=<hex HMAC-SHA256 of"
                    + " X-Audit-Export-Timestamp + \".\" + body>, keyed with the UTF-8 bytes of this string",
            example = "example-only-not-a-real-secret-00000000000",
            requiredMode = REQUIRED)
    private String signingSecret;
}
