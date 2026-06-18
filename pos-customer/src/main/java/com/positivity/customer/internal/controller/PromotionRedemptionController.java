package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.dto.PromotionRedemptionResponse;
import com.positivity.customer.internal.dto.RecordRedemptionRequest;
import com.positivity.customer.service.PromotionRedemptionService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Promotion Redemptions", description = "Operations for recording and querying promotion redemptions")
@RestController
@RequestMapping("/v1/promotions/redemptions")
public class PromotionRedemptionController {

    private final PromotionRedemptionService promotionRedemptionService;

    public PromotionRedemptionController(PromotionRedemptionService promotionRedemptionService) {
        this.promotionRedemptionService = promotionRedemptionService;
    }

    @Operation(summary = "Record promotion redemption", description = "Record a promotion redemption idempotently")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "201",
                        description = "Promotion redemption recorded",
                        content = @Content(schema = @Schema(implementation = PromotionRedemptionResponse.class))),
                @ApiResponse(responseCode = "409", description = "Duplicate redemption", content = @Content),
                @ApiResponse(
                        responseCode = "403",
                        description = "Forbidden - insufficient permissions",
                        content = @Content)
            })
    @PostMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"crm:promotion_redemption:record"})
    @PreAuthorize("hasAuthority('crm:promotion_redemption:record')")
    @EmitEvent(id = "PROMOTION_REDEMPTION_RECORD", apiVersion = "1")
    public ResponseEntity<PromotionRedemptionResponse> recordRedemption(
            @Valid @RequestBody RecordRedemptionRequest request) {
        PromotionRedemptionResponse response = promotionRedemptionService.recordRedemption(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "Get redemptions by customer",
            description = "Retrieve all recorded redemptions for a customer")
    @ApiResponse(
            responseCode = "200",
            description = "Promotion redemptions returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = PromotionRedemptionResponse.class))))
    @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    @GetMapping("/by-customer/{customerId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"crm:promotion_redemption:view"})
    @PreAuthorize("hasAuthority('crm:promotion_redemption:view')")
    @EmitEvent(id = "PROMOTION_REDEMPTION_LIST", apiVersion = "1")
    public ResponseEntity<List<PromotionRedemptionResponse>> getRedemptionsByCustomer(@PathVariable UUID customerId) {
        List<PromotionRedemptionResponse> response = promotionRedemptionService.getRedemptionsByCustomer(customerId);
        return ResponseEntity.ok(response);
    }
}
