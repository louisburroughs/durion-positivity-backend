package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.AuditExportWebhookRegistrationResponse;
import com.positivity.securityservice.internal.dto.AuditExportWebhookRequest;
import com.positivity.securityservice.internal.dto.AuditExportWebhookResponse;
import com.positivity.securityservice.internal.security.SecurityPermissions;
import com.positivity.securityservice.internal.service.AuditExportWebhookService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's tenant's audit export webhook destination (#2497): the one endpoint {@code WEBHOOK}
 * audit exports are delivered to. Changing it is {@code security:audit_export:webhook_manage}; reading
 * it is also open to {@code security:audit:export} holders, so whoever requests a WEBHOOK export (and
 * the assistant's write guard, acting as that caller) can see where the audit data would go. The
 * signing secret is returned by the PUT alone.
 *
 * <p>The literal {@code /webhook} path is preferred by Spring over {@code GET /v1/audit/exports/{jobId}}.
 */
@RestController
@RequestMapping("/v1/audit/exports/webhook")
@RequiredArgsConstructor
@Tag(name = "Audit Exports", description = "Asynchronous audit export job management")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class AuditExportWebhookController {

    private final AuditExportWebhookService webhookService;

    @PutMapping
    @PreAuthorize("hasAuthority('" + SecurityPermissions.AUDIT_EXPORT_WEBHOOK_MANAGE + "')")
    @EmitEvent(id = "SECURITY_AUDIT_EXPORT_WEBHOOK_REGISTER", apiVersion = "1")
    @Operation(
            operationId = "registerAuditExportWebhook",
            summary = "Register the Audit Export Webhook",
            description = """
                    Registers the HTTPS endpoint the caller's tenant receives WEBHOOK audit exports at, \
                    replacing any earlier one, and returns it with a newly issued signing secret.
                    Use this tool to set or rotate where audit exports are delivered; do not use it to export \
                    anything, which is requestAuditExport with deliveryMode WEBHOOK.
                    Preconditions: the caller must hold security:audit_export:webhook_manage; the url must be an \
                    absolute https URL with a DNS host name, no user info and no fragment, at most 2048 \
                    characters, and its host must resolve only to publicly routable addresses (never loopback, \
                    private, link-local, carrier-grade NAT, multicast, reserved or cloud metadata).
                    Required inputs: url in the request body.
                    Emits a SECURITY_AUDIT_EXPORT_WEBHOOK_REGISTER event; signingSecret appears only in this \
                    response, every earlier secret stops working, and each delivery carries \
                    X-Audit-Export-Signature, sha256= followed by the hex HMAC-SHA256 of the \
                    X-Audit-Export-Timestamp value, a dot and the body, keyed with that secret.
                    Returns 400 VALIDATION_ERROR with one fieldErrors entry on url per broken rule, and 403 \
                    without the permission.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Destination registered; the response carries the new signing secret, shown only once",
            content = @Content(schema = @Schema(implementation = AuditExportWebhookRegistrationResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "The url is not an allowed destination (VALIDATION_ERROR, fieldErrors on url)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Insufficient authority",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AuditExportWebhookRegistrationResponse> registerAuditExportWebhook(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The https endpoint WEBHOOK audit exports are delivered to.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "SIEM endpoint", value = """
                                                                    {"url":"https://siem.example.com/hooks/positivity-audit"}
                                                                    """)))
                    @RequestBody
                    @NonNull
                    AuditExportWebhookRequest request) {
        return ResponseEntity.ok(webhookService.register(request));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('" + SecurityPermissions.AUDIT_EXPORT_WEBHOOK_MANAGE + "', '"
            + SecurityPermissions.AUDIT_EXPORT + "')")
    @Operation(operationId = "getAuditExportWebhook", summary = "Get the Audit Export Webhook", description = """
                    Returns the HTTPS endpoint the caller's tenant receives WEBHOOK audit exports at, with when \
                    and by whom it was registered, and never its signing secret.
                    Use this tool to see where a WEBHOOK export would send audit data, and to name that \
                    destination to the user before requesting one; do not use it to change the destination, \
                    which is registerAuditExportWebhook.
                    Preconditions: the caller must hold security:audit_export:webhook_manage or \
                    security:audit:export.
                    Required inputs: none.
                    This is a read-only lookup and no events are emitted.
                    Returns 404 AUDIT_EXPORT_WEBHOOK_NOT_REGISTERED when the tenant has no destination, in which \
                    case a WEBHOOK export is refused.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "The registered destination, without its secret",
            content = @Content(schema = @Schema(implementation = AuditExportWebhookResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Insufficient authority",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No destination registered (AUDIT_EXPORT_WEBHOOK_NOT_REGISTERED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AuditExportWebhookResponse> getAuditExportWebhook() {
        return ResponseEntity.ok(webhookService.get());
    }

    @DeleteMapping
    @PreAuthorize("hasAuthority('" + SecurityPermissions.AUDIT_EXPORT_WEBHOOK_MANAGE + "')")
    @EmitEvent(id = "SECURITY_AUDIT_EXPORT_WEBHOOK_DELETE", apiVersion = "1")
    @Operation(operationId = "deleteAuditExportWebhook", summary = "Remove the Audit Export Webhook", description = """
                    Removes the HTTPS endpoint the caller's tenant receives WEBHOOK audit exports at, so no \
                    further export is sent outside the shop.
                    Use this tool to stop webhook delivery; use registerAuditExportWebhook instead to point it \
                    elsewhere or to rotate the signing secret.
                    Preconditions: the caller must hold security:audit_export:webhook_manage; a WEBHOOK job still \
                    being delivered is not sent once its destination is removed, and WEBHOOK requests answer 422 \
                    until another destination is registered.
                    Required inputs: none.
                    Emits a SECURITY_AUDIT_EXPORT_WEBHOOK_DELETE event and is idempotent, answering 204 even when \
                    nothing is registered.
                    Returns 403 when the caller lacks the permission.
                    """)
    @ApiResponse(responseCode = "204", description = "Destination removed, or none was registered")
    @ApiResponse(
            responseCode = "403",
            description = "Insufficient authority",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Void> deleteAuditExportWebhook() {
        webhookService.delete();
        return ResponseEntity.noContent().build();
    }
}
