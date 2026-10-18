package com.positivity.inboundmail.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inboundmail.internal.dto.StagedAttachmentBytes;
import com.positivity.inboundmail.internal.exception.StagedAttachmentNotFoundException;
import com.positivity.inboundmail.internal.service.AttachmentStaging;
import com.positivity.shared.error.ApiError;
import com.positivity.tenancy.TenantHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves a staged attachment's bytes to pos-accounting (CAP:550 S27, control 6). The bytes are written out
 * untouched: this class never parses, sniffs or transforms them.
 */
@RestController
@RequestMapping("/inbound-mail/v1/staged-attachments")
@RequiredArgsConstructor
@Tag(
        name = "Inbound mail staging",
        description = "Attachments of accepted emails, waiting for pos-accounting to collect them")
@PreAuthorize("isAuthenticated()")
public class StagedAttachmentController {

    static final String SHA256_HEADER = "X-Content-SHA256";

    private final AttachmentStaging attachmentStaging;

    @GetMapping(path = "/{ref}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @EmitEvent(id = "INBOUND_MAIL_STAGED_ATTACHMENT_GET", apiVersion = "1")
    @Operation(
            operationId = "getStagedInboundMailAttachment",
            summary = "Fetch one staged email attachment",
            description = """
            Returns the raw bytes of one attachment the edge staged from an accepted email, so pos-accounting can \
            hand them to its bill intake. The bytes are never parsed or altered; the digest of what was staged \
            travels in X-Content-SHA256 so the caller can verify what it received.
            Use this tool with the ref carried by an email-received command; do not use it to hunt for mail, there is \
            no listing endpoint, so take the ref from the command instead of guessing one, and a ref only ever works \
            for the tenant that owns it.
            Preconditions: the caller holds the shared secret, sends the tenant in X-Tenant-Id, and the ref has not \
            been collected or purged after the staging TTL.
            Required inputs: ref, the attachment's UUID from the command.
            Emits an INBOUND_MAIL_STAGED_ATTACHMENT_GET event.
            Returns 200 application/octet-stream with Content-Length, X-Content-SHA256, Cache-Control no-store and \
            X-Content-Type-Options nosniff, or 404 STAGED_ATTACHMENT_NOT_FOUND with one identical body whether the \
            ref is unknown, another tenant's, collected or purged.
            """)
    @Parameter(
            in = ParameterIn.HEADER,
            name = TenantHeaders.HTTP_TENANT_ID,
            description = "The caller's bound tenant; without it the edge answers 401 TENANT_REQUIRED",
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(
            responseCode = "200",
            description = "The attachment's bytes",
            content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE))
    @ApiResponse(
            responseCode = "401",
            description =
                    "Missing or wrong X-Inbound-Mail-Secret, the edge's own secret unset, or no tenant (TENANT_REQUIRED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "STAGED_ATTACHMENT_NOT_FOUND: unknown, another tenant's, collected or purged ref",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Resource> get(@PathVariable("ref") UUID ref) {
        StagedAttachmentBytes staged = attachmentStaging.fetch(ref).orElseThrow(StagedAttachmentNotFoundException::new);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(staged.sizeBytes())
                .header(SHA256_HEADER, staged.sha256())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(new ByteArrayResource(staged.content()));
    }
}
