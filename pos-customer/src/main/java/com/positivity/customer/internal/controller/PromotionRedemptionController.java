package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.dto.PromotionRedemptionResponse;
import com.positivity.customer.internal.dto.RecordRedemptionRequest;
import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.internal.service.PromotionRedemptionService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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

    @Operation(operationId = "recordPromotionRedemption", summary = "Record Promotion Redemption", description = """
                    Records that a promotion was redeemed on a workorder, increments the promotion's usage \
                    counter, and stamps the recording user; the pair of promotionId and workorderId is the \
                    idempotency key.
                    Use this tool when a discount is actually applied at checkout or invoicing; use \
                    listPromotionRedemptionsByCustomer instead to read what a customer has redeemed.
                    Preconditions: no redemption may already exist for the same promotionId and workorderId \
                    pair; the referenced promotion, customer, and workorder ids are recorded as supplied \
                    without cross-service validation.
                    Required inputs: promotionId, customerId, and workorderId (UUIDs), discountAmount, \
                    discountType (max 50), and promotionCode (max 100); invoiceId, campaignCode, and \
                    redemptionTimestamp (defaulting to now) are optional, and recordedOverLimit defaults to \
                    false and switches the stored status to RECORDED_OVER_LIMIT.
                    Emits a PROMOTION_REDEMPTION_RECORD event and publishes a redemption-recorded fact for \
                    every redemption so marketing keeps a non-attributed baseline.
                    Returns 409 when the promotion has already been redeemed on that workorder, and 400 \
                    when a required field is missing.
                    """)
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
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PROMOTION_REDEMPTION_RECORD + "')")
    @EmitEvent(id = "PROMOTION_REDEMPTION_RECORD", apiVersion = "1")
    public ResponseEntity<PromotionRedemptionResponse> recordRedemption(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "The redemption to record, keyed for idempotency by promotionId and workorderId.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Coupon redemption", value = """
                                                                    {"promotionId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a62",
                                                                     "customerId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "workorderId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a63",
                                                                     "discountAmount":25.00,
                                                                     "discountType":"FIXED_AMOUNT",
                                                                     "promotionCode":"SPRING25",
                                                                     "campaignCode":"SPRING-2026"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    RecordRedemptionRequest request) {
        PromotionRedemptionResponse response = promotionRedemptionService.recordRedemption(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            operationId = "listPromotionRedemptionsByCustomer",
            summary = "List Customer Promotion Redemptions",
            description = """
                    Returns every promotion redemption recorded for one customer, including discount \
                    amounts, codes, statuses, and timestamps.
                    Use this tool when reviewing a customer's redemption history, for example to enforce \
                    per-customer limits; use recordPromotionRedemption instead to record a new redemption.
                    Preconditions: none; an unknown customerId yields an empty list rather than an error.
                    Required inputs: customerId (UUID) as a path parameter; there is no request body.
                    Emits a PROMOTION_REDEMPTION_LIST audit event; no state changes occur.
                    Returns 200 with an empty list rather than an error when the customer has no \
                    redemptions.
                    """)
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
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PROMOTION_REDEMPTION_VIEW + "')")
    @EmitEvent(id = "PROMOTION_REDEMPTION_LIST", apiVersion = "1")
    public ResponseEntity<List<PromotionRedemptionResponse>> getRedemptionsByCustomer(@PathVariable UUID customerId) {
        List<PromotionRedemptionResponse> response = promotionRedemptionService.getRedemptionsByCustomer(customerId);
        return ResponseEntity.ok(response);
    }
}
