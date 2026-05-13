package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.receiving.CreateReceivingSessionRequest;
import com.positivity.inventory.internal.dto.receiving.CrossDockRequest;
import com.positivity.inventory.internal.dto.receiving.CrossDockResponse;
import com.positivity.inventory.internal.dto.receiving.ReceiveItemsRequest;
import com.positivity.inventory.internal.dto.receiving.ReceiveItemsResponse;
import com.positivity.inventory.internal.dto.receiving.ReceivingSessionResponse;
import com.positivity.inventory.service.ReceivingService;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/receiving")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Receiving", description = "Receiving session creation, item receiving, and cross-dock execution endpoints")
public class ReceivingController {

    private static final String NO_CURRENT_USER = "No current user";
    private final ReceivingService receivingService;

    @PostMapping("/sessions")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:receiving:create"})
    @PreAuthorize("hasAuthority('inventory:receiving:create')")
    @EmitEvent(id = "INVENTORY_RECEIVING_SESSION_CREATE", apiVersion = "1")
    @Operation(
            summary = "Create receiving session",
            description = "Creates a receiving session from a source document (PO/ASN) using MANUAL or SCAN entry mode",
            tags = {"Receiving"})
    @ApiResponse(
            responseCode = "201",
            description = "Receiving session created",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ReceivingSessionResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure or source document already fully received",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required receiving:create authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Source document not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ReceivingSessionResponse> createReceivingSession(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "Receiving session creation payload",
                            content = @Content(schema = @Schema(implementation = CreateReceivingSessionRequest.class)))
                    @Valid
                    @RequestBody
                    CreateReceivingSessionRequest request) {

        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));

        ReceivingSessionResponse response = receivingService.createReceivingSession(request, actorUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/sessions/{sessionId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:receiving:view"})
    @PreAuthorize("hasAuthority('inventory:receiving:view')")
    @EmitEvent(id = "INVENTORY_RECEIVING_SESSION_GET", apiVersion = "1")
    @Operation(
            summary = "Get receiving session",
            description = "Retrieves receiving session details by session identifier",
            tags = {"Receiving"})
    @ApiResponse(
            responseCode = "200",
            description = "Receiving session found",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ReceivingSessionResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required receiving:view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Receiving session not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ReceivingSessionResponse> getReceivingSession(
            @Parameter(description = "Receiving session identifier", required = true) @PathVariable UUID sessionId) {

        ReceivingSessionResponse response = receivingService.getReceivingSession(sessionId);
        return ResponseEntity.ok(response);
    }

    /**
     * Records actual received quantities for a session, generating ledger entries.
     * ADR-0017: Returns 200 OK (updating existing resource).
     * ADR-0018: actorUserId from authenticated security context.
     */
    @PostMapping("/sessions/{sessionId}/receive")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:receiving:complete"})
    @PreAuthorize("hasAuthority('inventory:receiving:complete')")
    @EmitEvent(id = "INVENTORY_RECEIVING_SESSION_COMPLETE", apiVersion = "1")
    @Operation(
            summary = "Receive items into staging",
            description =
                    "Records received quantities for receiving session lines and generates receipt ledger/variance records",
            tags = {"Receiving"})
    @ApiResponse(
            responseCode = "200",
            description = "Items received successfully",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ReceiveItemsResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required receiving:complete authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Receiving session not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ReceiveItemsResponse> receiveItemsIntoStaging(
            @Parameter(description = "Receiving session identifier", required = true) @PathVariable UUID sessionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "Receive lines payload",
                            content = @Content(schema = @Schema(implementation = ReceiveItemsRequest.class)))
                    @Valid
                    @RequestBody
                    ReceiveItemsRequest request) {

        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));

        ReceiveItemsResponse response = receivingService.receiveItemsIntoStaging(sessionId, request, actorUserId);
        return ResponseEntity.ok(response);
    }

    /**
     * Cross-docks a receiving line directly to a workorder.
     * ADR-0017: 200 OK on success, 400 for closed workorder, 403 for part mismatch
     * without permission.
     * ADR-0018: actorUserId from authenticated security context.
     * ADR-0001: Dual ledger entries (GOODS_RECEIVED + GOODS_ISSUE) created
     * atomically.
     */
    @PostMapping("/sessions/{sessionId}/lines/{lineId}/cross-dock")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:receiving:complete", "inventory:issue:parts"})
    @PreAuthorize("hasAuthority('inventory:receiving:complete') and hasAuthority('inventory:issue:parts')")
    @EmitEvent(id = "INVENTORY_RECEIVING_CROSSDOCK", apiVersion = "1")
    @Operation(
            summary = "Cross-dock receiving line to workorder",
            description =
                    "Cross-docks received quantity from a session line directly to a workorder line with atomic receipt and issue ledger events",
            tags = {"Receiving"})
    @ApiResponse(
            responseCode = "200",
            description = "Cross-dock completed",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CrossDockResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request or closed workorder",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required authority or part-match override permission",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Receiving session or line not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<CrossDockResponse> crossDockLineToWorkorder(
            @Parameter(description = "Receiving session identifier", required = true) @PathVariable UUID sessionId,
            @Parameter(description = "Receiving line identifier", required = true) @PathVariable UUID lineId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "Cross-dock request payload",
                            content = @Content(schema = @Schema(implementation = CrossDockRequest.class)))
                    @Valid
                    @RequestBody
                    CrossDockRequest request) {

        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));

        CrossDockResponse response = receivingService.crossDockLineToWorkorder(sessionId, lineId, request, actorUserId);
        return ResponseEntity.ok(response);
    }
}
