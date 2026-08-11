package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.dto.InquiryAcceptedResponse;
import com.positivity.customer.internal.dto.SubmitInquiryRequest;
import com.positivity.customer.internal.enums.InquiryStatus;
import com.positivity.customer.service.InquiryService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated inbound inquiry capture (Story #1154).
 *
 * <p><strong>This is the only unauthenticated write surface in pos-customer.</strong> It is
 * therefore off unless {@code pos.customer.inquiry.public.enabled=true} is set deliberately,
 * and the matching permit rule in {@code SecurityConfig} is bound to the same flag — so a
 * default deployment refuses these requests rather than accepting them because someone forgot
 * to lock it down.
 *
 * <p>Before enabling, three things must be true, and none of them can be enforced from here:
 *
 * <ol>
 *   <li>A captcha or equivalent bot check is applied at the edge. There is deliberately no
 *       stub verifier in this module — a no-op that looks like a captcha is worse than none.
 *   <li>The gateway is configured to route this path without requiring a JWT. It currently has
 *       exactly one auth bypass (the login endpoint), so this is a conscious change to the
 *       gateway's trust model, not a config tweak.
 *   <li>Edge rate limiting is in place. The per-source ceiling enforced in the service is a
 *       backstop for correctness, not a defence against volume.
 * </ol>
 *
 * <p>The response carries only an id and a status: echoing the submission back, or hinting
 * that the contact matches an existing party, would make this a customer-enumeration oracle.
 */
@Slf4j
@Tag(name = "CRM Public Inquiry", description = "Unauthenticated inbound inquiry capture")
@RestController
@RequestMapping("/v1/crm/public/inquiries")
@ConditionalOnProperty(prefix = "pos.customer.inquiry.public", name = "enabled", havingValue = "true")
public class PublicInquiryController {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    /**
     * Half a SHA-256 is ample to distinguish submitters for rate limiting and far too little to
     * work backwards to an address, which is the whole point — abuse triage does not need to
     * know who visited.
     */
    private static final int FINGERPRINT_LENGTH = 32;

    private final InquiryService inquiryService;

    public PublicInquiryController(InquiryService inquiryService) {
        this.inquiryService = inquiryService;
    }

    @Operation(
            summary = "Submit an inquiry",
            description =
                    "Public, unauthenticated capture of a service or fleet-quote request. Rate-limited per submitter; returns only a reference id.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "202",
                description = "Inquiry accepted",
                content = @Content(schema = @Schema(implementation = InquiryAcceptedResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content),
        @ApiResponse(responseCode = "429", description = "Too many submissions", content = @Content)
    })
    @PostMapping
    @PreAuthorize("permitAll()")
    @EmitEvent(id = "CRM_PUBLIC_INQUIRY_SUBMIT", apiVersion = "1")
    public ResponseEntity<InquiryAcceptedResponse> submit(
            @Valid @RequestBody SubmitInquiryRequest request, HttpServletRequest httpRequest) {
        UUID inquiryId = inquiryService.captureFromPublicForm(request, fingerprint(httpRequest));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new InquiryAcceptedResponse(inquiryId, InquiryStatus.NEW.name()));
    }

    /**
     * A one-way fingerprint of the submitter's origin. The raw address is never stored, so the
     * inquiry table cannot become a visitor log.
     */
    private static @Nullable String fingerprint(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader(FORWARDED_FOR);
        String source =
                forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
        if (source == null || source.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hex = HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
            return hex.substring(0, FINGERPRINT_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to fingerprint public submissions", e);
        }
    }
}
