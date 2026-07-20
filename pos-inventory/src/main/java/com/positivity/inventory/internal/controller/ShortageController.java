package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.ShortageOptionDto;
import com.positivity.inventory.internal.dto.ShortageResolutionResultDto;
import com.positivity.inventory.internal.dto.ShortageResolveRequest;
import com.positivity.inventory.internal.observability.BusinessSpanSupport;
import com.positivity.inventory.service.ShortageResolutionService;
import com.positivity.shared.error.ApiError;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/shortage")
@RequiredArgsConstructor
@Tag(name = "Shortage Resolution", description = "Shortage resolution recommendation endpoints")
public class ShortageController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ShortageController.class);
    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("pos-inventory");
    private static final String DOMAIN = "inventory";
    private static final String TEAM = "inventory-eng";

    private final ShortageResolutionService shortageResolutionService;

    @GetMapping("/options")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:shortage:view"})
    @PreAuthorize("hasAuthority('inventory:shortage:view')")
    @Operation(
            operationId = "listShortageOptions",
            summary = "List shortage options",
            description = "Returns available shortage resolution options for an allocation.",
            tags = {"Shortage Resolution"})
    @ApiResponse(
            responseCode = "200",
            description = "Shortage options returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ShortageOptionDto.class))))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required shortage view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<ShortageOptionDto>> listShortageOptions(@RequestParam UUID allocationId) {
        return ResponseEntity.ok(shortageResolutionService.listShortageOptions(allocationId));
    }

    @PostMapping("/resolve")
    @EmitEvent(id = "INVENTORY_SHORTAGE_RESOLVE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:shortage:resolve"})
    @PreAuthorize("hasAuthority('inventory:shortage:resolve')")
    @Operation(
            operationId = "resolveShortage",
            summary = "Resolve shortage",
            description = "Resolves inventory shortage using a selected strategy.",
            tags = {"Shortage Resolution"})
    @ApiResponse(
            responseCode = "200",
            description = "Shortage resolved",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ShortageResolutionResultDto.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required shortage resolution authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Unable to resolve shortage under current constraints",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ShortageResolutionResultDto> resolveShortage(
            @Valid @RequestBody ShortageResolveRequest request) {
        Span span = TRACER.spanBuilder("Resolve Inventory Shortage").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Resolve Inventory Shortage");
        span.setAttribute("app.operation.type", "command");
        span.setAttribute("app.domain", DOMAIN);
        span.setAttribute("app.team", TEAM);
        try (Scope scope = span.makeCurrent()) {
            ShortageResolutionResultDto response = shortageResolutionService.resolveShortage(request);
            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            BusinessSpanSupport.logWithTraceContext(log, "Resolved inventory shortage");
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }
}
