package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.dto.NltiRequestDTO;
import com.positivity.mcp.internal.dto.NltiResponseV1;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.service.NltiRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"nlti:request:submit"})
@RequestMapping("/v1/nlt")
@Tag(name = "NLTI", description = "Natural Language Task Interface")
public class NltiController {

    private final NltiRequestService nltiRequestService;

    NltiController(@NonNull NltiRequestService nltiRequestService) {
        this.nltiRequestService = nltiRequestService;
    }

    @PostMapping("/requests")
    @EmitEvent(id = "NLTI_REQUEST_SUBMIT", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + McpPermissions.NLTI_REQUEST_SUBMIT + "')")
    @Operation(
            summary = "Submit a natural language task request",
            description = "Submit a natural language task request for asynchronous MCP processing and correlation tracking")
    ResponseEntity<NltiResponseV1> submitRequest(
            @Valid @RequestBody @NonNull NltiRequestDTO request,
            @RequestHeader(value = NltiCorrelationIdSupport.CORRELATION_ID_HEADER, required = false)
                    String correlationIdHeader,
            @NonNull HttpServletRequest servletRequest) {
        UUID resolvedCorrelationId = NltiCorrelationIdSupport.resolveFromHeader(correlationIdHeader);
        servletRequest.setAttribute(NltiCorrelationIdSupport.CORRELATION_ID_ATTRIBUTE, resolvedCorrelationId);
        NltiResponseV1 response = nltiRequestService.submit(request, resolvedCorrelationId);
        return ResponseEntity.accepted()
                .header(
                        NltiCorrelationIdSupport.CORRELATION_ID_HEADER,
                        response.correlationId().toString())
                .body(response);
    }
}
