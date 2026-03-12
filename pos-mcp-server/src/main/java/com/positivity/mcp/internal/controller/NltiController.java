package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.dto.NltiRequestDTO;
import com.positivity.mcp.internal.dto.NltiResponseV1;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.service.NltiRequestService;
import com.positivity.shared.id.UUIDv7Generator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/nlt")
@Tag(name = "NLTI", description = "Natural Language Task Interface")
class NltiController {

    private final NltiRequestService nltiRequestService;

    NltiController(@NonNull NltiRequestService nltiRequestService) {
        this.nltiRequestService = nltiRequestService;
    }

    @PostMapping("/requests")
    @EmitEvent(id = "NLTI_REQUEST_SUBMIT", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + McpPermissions.NLTI_REQUEST_SUBMIT + "')")
    @Operation(summary = "Submit a natural language task request")
    ResponseEntity<NltiResponseV1> submitRequest(
            @Valid @RequestBody @NonNull NltiRequestDTO request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationIdHeader) {
        UUID resolvedCorrelationId = parseOrGenerateCorrelationId(correlationIdHeader);
        NltiResponseV1 response = nltiRequestService.submit(request, resolvedCorrelationId);
        return ResponseEntity.accepted()
                .header("X-Correlation-Id", response.correlationId().toString())
                .body(response);
    }

    private UUID parseOrGenerateCorrelationId(String header) {
        if (header != null && !header.isBlank()) {
            try {
                return UUID.fromString(header);
            } catch (IllegalArgumentException ignored) {
                // fall through to generate
            }
        }
        return UUIDv7Generator.generate();
    }
}
