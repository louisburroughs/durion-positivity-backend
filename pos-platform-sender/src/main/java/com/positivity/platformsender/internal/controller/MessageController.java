package com.positivity.platformsender.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.platformsender.internal.dto.SendMessageRequest;
import com.positivity.platformsender.internal.dto.SendMessageResponse;
import com.positivity.platformsender.internal.service.MessageSendService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
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

    private final MessageSendService messageSendService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @EmitEvent(id = "PLATFORM_SENDER_MESSAGE_SEND", apiVersion = "1")
    @Operation(
            summary = "Deliver one rendered message",
            description = "Resolves the recipient's address, hands the message to the provider and returns the"
                    + " provider message id. A replayed messageId answers 200 with the original ids and never"
                    + " delivers twice.")
    @ApiResponse(responseCode = "202", description = "Accepted for delivery")
    @ApiResponse(responseCode = "200", description = "Idempotent replay of an accepted messageId")
    @ApiResponse(
            responseCode = "422",
            description = "Permanent refusal: no resolvable address, or the provider refused the message",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = "Transient: nothing was delivered; retry with backoff",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<SendMessageResponse> send(@Valid @RequestBody SendMessageRequest request) {
        MessageSendService.SendResult result = messageSendService.send(request);
        return ResponseEntity.status(result.replay() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(result.response());
    }
}
