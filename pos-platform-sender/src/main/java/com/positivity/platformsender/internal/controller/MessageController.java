package com.positivity.platformsender.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.platformsender.internal.dto.SendMessageRequest;
import com.positivity.platformsender.internal.dto.SendMessageResponse;
import com.positivity.platformsender.internal.service.MessageSendService;
import com.positivity.shared.error.ApiError;
import com.positivity.tenancy.TenantHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The FI-2 send API ({@code durion/domains/positivity/PLATFORM_SENDER_CONTRACT.md} §1). Called
 * service-to-service with {@code X-Pos-Sender-Secret} and the caller's {@code X-Tenant-Id}; the
 * gateway has no route here.
 */
@RestController
@RequestMapping(value = "/platform-sender/v1/messages", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Platform sender", description = "Email and SMS delivery for campaign sends (FI-2)")
@PreAuthorize("isAuthenticated()")
public class MessageController {

    private static final String SEND_EXAMPLE = """
            {"messageId":"01990000-0000-7000-8000-000000000001","channel":"EMAIL",
             "recipientPartyId":"01990000-0000-7000-8000-0000000000a1","campaignCode":"SPRING24",
             "subject":"Time for a tyre check","body":"<p>Book your spring tyre check.</p>"}
            """;

    private final MessageSendService messageSendService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @EmitEvent(id = "PLATFORM_SENDER_MESSAGE_SEND", apiVersion = "1")
    @Operation(operationId = "sendPlatformMessage", summary = "Deliver one rendered message", description = """
            Resolves the recipient's email address or mobile number from the sender's replicas of pos-customer and \
            pos-people-contact, hands the rendered message to Amazon SES or AWS End User Messaging, and returns the \
            provider message id that later sender.outcomes.v1 facts carry.
            Use this tool once per campaign send, keyed by messageId; do not use it to retry under a new key, replay \
            the same messageId instead, which answers 200 with the original ids and never delivers twice.
            Preconditions: the caller holds the shared secret, its tenant is bound, and the recipient party is in \
            the sender's replicas.
            Required inputs: messageId, channel, recipientPartyId, campaignCode and body; subject is required for \
            EMAIL, and contactId names the person party whose address is used when it differs from the recipient.
            Emits a PLATFORM_SENDER_MESSAGE_SEND event; the provider's delivery, bounce, complaint, open and click \
            reports follow on sender.outcomes.v1.
            Returns 202 with the provider message id, 422 when no address resolves or the provider refuses the \
            message for good, and 503 when nothing was delivered (retry the same messageId) or when the provider gave \
            no answer (PROVIDER_NO_RESPONSE), in which case every replay answers 503 SEND_IN_FLIGHT.
            """)
    @Parameter(
            in = ParameterIn.HEADER,
            name = TenantHeaders.HTTP_TENANT_ID,
            description = "The caller's bound tenant. Without it the sender binds its transitional default"
                    + " tenant, or answers 401 TENANT_REQUIRED when none is configured",
            schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(responseCode = "202", description = "Accepted for delivery")
    @ApiResponse(responseCode = "200", description = "Idempotent replay of an accepted messageId")
    @ApiResponse(
            responseCode = "400",
            description = "Malformed request (VALIDATION_FAILED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Missing or wrong X-Pos-Sender-Secret, or no tenant to bind (TENANT_REQUIRED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Permanent refusal: no resolvable address, or the provider refused the message",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = "Transient: nothing was delivered, or the provider gave no answer"
                    + " (PROVIDER_NO_RESPONSE, after which replays answer SEND_IN_FLIGHT); retry the same messageId",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<SendMessageResponse> send(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "One rendered message for one recipient, keyed by the caller's messageId.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            examples = @ExampleObject(name = "Campaign email", value = SEND_EXAMPLE)))
                    @Valid
                    @RequestBody
                    SendMessageRequest request) {
        MessageSendService.SendResult result = messageSendService.send(request);
        return ResponseEntity.status(result.replay() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(result.response());
    }
}
