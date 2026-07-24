package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.ShortageOptionDto;
import com.positivity.inventory.internal.dto.ShortageResolutionResultDto;
import com.positivity.inventory.internal.dto.ShortageResolveRequest;
import com.positivity.inventory.service.ShortageResolutionService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
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

    private final ShortageResolutionService shortageResolutionService;

    @GetMapping("/options")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:shortage:view"})
    @PreAuthorize("hasAuthority('inventory:shortage:view')")
    @Operation(
            operationId = "listShortageOptions",
            summary = "List computed shortage options",
            description = "Computes the resolution options (backorder, substitute, transfer-in, emergency purchase,"
                    + " cancel) for a shortage, each with an expected-resolution date and a cost delta where"
                    + " computable.",
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
    public ResponseEntity<List<ShortageOptionDto>> listShortageOptions(
            @Parameter(description = "Allocation experiencing the shortage") @RequestParam UUID allocationId,
            @Parameter(description = "SKU / stock-item identifier that is short") @RequestParam @NotBlank String sku,
            @Parameter(description = "Quantity that is short") @RequestParam @Positive int shortQuantity,
            @Parameter(description = "Workorder line whose demand is short") @RequestParam(required = false)
                    UUID workorderLineId,
            @Parameter(description = "Site the demand is short at") @RequestParam(required = false) UUID locationId) {
        return ResponseEntity.ok(shortageResolutionService.computeShortageOptions(
                allocationId, workorderLineId, sku, shortQuantity, locationId));
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
        return ResponseEntity.ok(shortageResolutionService.resolveShortage(request));
    }
}
