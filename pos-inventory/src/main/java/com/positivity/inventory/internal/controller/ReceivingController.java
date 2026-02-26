package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.receiving.CrossDockRequest;
import com.positivity.inventory.internal.dto.receiving.CrossDockResponse;
import com.positivity.inventory.internal.dto.receiving.CreateReceivingSessionRequest;
import com.positivity.inventory.internal.dto.receiving.ReceiveItemsRequest;
import com.positivity.inventory.internal.dto.receiving.ReceiveItemsResponse;
import com.positivity.inventory.internal.dto.receiving.ReceivingSessionResponse;
import com.positivity.inventory.service.ReceivingService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/receiving")
@RequiredArgsConstructor
@Slf4j
public class ReceivingController {

    private final ReceivingService receivingService;

    @PostMapping("/sessions")
    @PreAuthorize("hasAuthority('inventory:receiving:create')")
    @EmitEvent(id = "INVENTORY_RECEIVING_SESSION_CREATE", apiVersion = "1")
    public ResponseEntity<ReceivingSessionResponse> createReceivingSession(
            @Valid @RequestBody CreateReceivingSessionRequest request,
            @RequestHeader("X-User") String actorUserId) {

        ReceivingSessionResponse response = receivingService.createReceivingSession(request, actorUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/sessions/{sessionId}")
    @PreAuthorize("hasAuthority('inventory:receiving:view')")
    @EmitEvent(id = "INVENTORY_RECEIVING_SESSION_GET", apiVersion = "1")
    public ResponseEntity<ReceivingSessionResponse> getReceivingSession(
            @PathVariable UUID sessionId) {

        ReceivingSessionResponse response = receivingService.getReceivingSession(sessionId);
        return ResponseEntity.ok(response);
    }

    /**
     * Records actual received quantities for a session, generating ledger entries.
     * ADR-0017: Returns 200 OK (updating existing resource).
     * ADR-0018: actorUserId from X-User header.
     */
    @PostMapping("/sessions/{sessionId}/receive")
    @PreAuthorize("hasAuthority('inventory:receiving:complete')")
    @EmitEvent(id = "INVENTORY_RECEIVING_SESSION_COMPLETE", apiVersion = "1")
    public ResponseEntity<ReceiveItemsResponse> receiveItemsIntoStaging(
            @PathVariable UUID sessionId,
            @Valid @RequestBody ReceiveItemsRequest request,
            @RequestHeader("X-User") String actorUserId) {

        ReceiveItemsResponse response = receivingService.receiveItemsIntoStaging(sessionId, request, actorUserId);
        return ResponseEntity.ok(response);
    }

    /**
     * Cross-docks a receiving line directly to a workorder.
     * ADR-0017: 200 OK on success, 400 for closed workorder, 403 for part mismatch
     * without permission.
     * ADR-0018: actorUserId from X-User header.
     * ADR-0001: Dual ledger entries (GOODS_RECEIVED + GOODS_ISSUE) created
     * atomically.
     */
    @PostMapping("/sessions/{sessionId}/lines/{lineId}/cross-dock")
    @PreAuthorize("hasAuthority('inventory:receiving:complete') and hasAuthority('inventory:issue:parts')")
    @EmitEvent(id = "INVENTORY_RECEIVING_CROSSDOCK", apiVersion = "1")
    public ResponseEntity<CrossDockResponse> crossDockLineToWorkorder(
            @PathVariable UUID sessionId,
            @PathVariable UUID lineId,
            @Valid @RequestBody CrossDockRequest request,
            @RequestHeader("X-User") String actorUserId) {

        CrossDockResponse response = receivingService.crossDockLineToWorkorder(sessionId, lineId, request, actorUserId);
        return ResponseEntity.ok(response);
    }
}