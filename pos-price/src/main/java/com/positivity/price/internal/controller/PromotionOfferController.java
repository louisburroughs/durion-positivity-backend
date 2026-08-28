package com.positivity.price.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.price.internal.dto.ApplyPromotionRequest;
import com.positivity.price.internal.dto.ApplyPromotionResponse;
import com.positivity.price.internal.dto.CreatePromotionOfferRequest;
import com.positivity.price.internal.dto.PromotionOfferMapper;
import com.positivity.price.internal.dto.PromotionOfferResponse;
import com.positivity.price.internal.security.PricingPermissions;
import com.positivity.price.internal.service.PromotionOfferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Controller for promotion offer lifecycle operations. Issue: #97 */
@RestController
@RequestMapping("/v1/promotions/offers")
@Tag(name = "Promotion Offers", description = "Promotion offer lifecycle operations")
public class PromotionOfferController {

    private static final String APPLY_PROMOTION_EXAMPLE = """
            {"promotionCode":"SUMMER20",
             "estimateContext":{
               "estimateId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01",
               "customerId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02",
               "vehicleId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a03",
               "lineItems":[{"sku":"P001","quantity":1,"unitPrice":500.00}],
               "subtotal":500.00,
               "appliedPromoCodes":[]}}
            """;

    private final PromotionOfferService promotionOfferService;

    public PromotionOfferController(PromotionOfferService promotionOfferService) {
        this.promotionOfferService = promotionOfferService;
    }

    @PostMapping
    @EmitEvent(id = "PROMOTION_OFFER_CREATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"pricing:promotion:manage"})
    @PreAuthorize("hasAuthority('" + PricingPermissions.PROMOTION_MANAGE + "')")
    @Operation(operationId = "createPromotionOffer", summary = "Create Promotion Offer", description = """
                    Creates a promotion offer in DRAFT status with a unique promo code, a validity window, and a \
                    discount policy.
                    Use this tool to define a new promotion; do not use activatePromotionOffer, which only transitions \
                    an existing offer to ACTIVE, and note that a DRAFT offer cannot be applied until activated.
                    Preconditions: no offer may already exist with the same promoCode, and startDate must not be after \
                    endDate.
                    Required inputs: promoCode (max 64 characters), name, discountType (PERCENT_LABOR, PERCENT_PARTS, \
                    or FIXED_INVOICE), discountValue (minimum 0.01, two decimal places), startDate, and endDate; \
                    usageLimit and storeCode are optional and default to unlimited usage and no store scoping.
                    Emits a PROMOTION_OFFER_CREATE event and persists the offer with status DRAFT and a zero usage \
                    count.
                    Returns 409 when the promoCode already exists, and 422 when startDate is after endDate.
                    """)
    @ApiResponse(responseCode = "201", description = "Promotion offer created.")
    @ApiResponse(responseCode = "400", description = "Invalid promotion offer request.")
    @ApiResponse(responseCode = "409", description = "Promotion code already exists.")
    @ApiResponse(responseCode = "422", description = "startDate is after endDate.")
    @ApiResponse(responseCode = "403", description = "Forbidden.")
    public ResponseEntity<PromotionOfferResponse> createOffer(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Promotion offer definition: promo code, discount policy, and validity window.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Percent labor promotion", value = """
                                                                    {"promoCode":"SUMMER20",
                                                                     "name":"Summer Labor Discount",
                                                                     "description":"20% off labor for seasonal service",
                                                                     "discountType":"PERCENT_LABOR",
                                                                     "discountValue":20.00,
                                                                     "startDate":"2026-06-01",
                                                                     "endDate":"2026-08-31",
                                                                     "usageLimit":100,
                                                                     "storeCode":"NYC-001"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreatePromotionOfferRequest request) {
        var offer = promotionOfferService.createOffer(request);
        var response = PromotionOfferMapper.toResponse(offer);
        URI location = URI.create("/v1/promotions/offers/" + response.getPromotionOfferId());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"pricing:promotion:view"})
    @PreAuthorize("hasAuthority('" + PricingPermissions.PROMOTION_VIEW + "')")
    @Operation(operationId = "getPromotionOfferById", summary = "Get Promotion Offer By ID", description = """
                    Returns the full promotion offer, including status, discount policy, validity window, and usage \
                    counters.
                    Use this tool when the promotion offer's UUID is already known; use getPromotionOfferByCode instead \
                    when only the customer-facing promo code is available.
                    Preconditions: the promotion offer must exist.
                    Required inputs: id (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no promotion offer exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Promotion offer returned.")
    @ApiResponse(responseCode = "404", description = "Promotion offer not found.")
    @ApiResponse(responseCode = "403", description = "Forbidden.")
    public ResponseEntity<PromotionOfferResponse> getOfferById(@PathVariable("id") UUID promotionOfferId) {
        var offer = promotionOfferService.getOfferById(promotionOfferId);
        return ResponseEntity.ok(PromotionOfferMapper.toResponse(offer));
    }

    @GetMapping("/by-code/{promoCode}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"pricing:promotion:view"})
    @PreAuthorize("hasAuthority('" + PricingPermissions.PROMOTION_VIEW + "')")
    @Operation(operationId = "getPromotionOfferByCode", summary = "Get Promotion Offer By Code", description = """
                    Returns the promotion offer that owns the supplied customer-facing promo code.
                    Use this tool when only the promo code from marketing material is known; use \
                    getPromotionOfferById instead when the offer's UUID is available.
                    Preconditions: an offer with exactly that promoCode must exist; the lookup is an exact match on \
                    the code.
                    Required inputs: promoCode (max 64 characters) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no promotion offer exists for the supplied code.
                    """)
    @ApiResponse(responseCode = "200", description = "Promotion offer returned.")
    @ApiResponse(responseCode = "404", description = "Promotion offer not found.")
    @ApiResponse(responseCode = "403", description = "Forbidden.")
    public ResponseEntity<PromotionOfferResponse> getOfferByCode(@PathVariable("promoCode") String promoCode) {
        var offer = promotionOfferService.getOfferByCode(promoCode);
        return ResponseEntity.ok(PromotionOfferMapper.toResponse(offer));
    }

    @PatchMapping("/{id}/activate")
    @EmitEvent(id = "PROMOTION_OFFER_ACTIVATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"pricing:promotion:manage"})
    @PreAuthorize("hasAuthority('" + PricingPermissions.PROMOTION_MANAGE + "')")
    @Operation(operationId = "activatePromotionOffer", summary = "Activate Promotion Offer", description = """
                    Transitions a promotion offer to ACTIVE so applyPromotionOffer will accept its promo code within \
                    the validity window.
                    Use this tool to publish a DRAFT offer or re-enable an INACTIVE one; do not use \
                    createPromotionOffer, which creates a new offer that still starts in DRAFT.
                    Preconditions: the offer must exist, must not be EXPIRED, and its endDate must not already be in \
                    the past.
                    Required inputs: id (UUID) as a path parameter; there is no request body.
                    Emits a PROMOTION_OFFER_ACTIVATE event and sets the status to ACTIVE immediately.
                    Returns 404 when the offer does not exist, and 422 when the offer is EXPIRED or its end date has \
                    already passed.
                    """)
    @ApiResponse(responseCode = "200", description = "Promotion offer activated.")
    @ApiResponse(responseCode = "404", description = "Promotion offer not found.")
    @ApiResponse(responseCode = "422", description = "Promotion offer cannot be activated in current state.")
    @ApiResponse(responseCode = "403", description = "Forbidden.")
    public ResponseEntity<PromotionOfferResponse> activateOffer(@PathVariable("id") UUID promotionOfferId) {
        var offer = promotionOfferService.activateOffer(promotionOfferId);
        return ResponseEntity.ok(PromotionOfferMapper.toResponse(offer));
    }

    @PatchMapping("/{id}/deactivate")
    @EmitEvent(id = "PROMOTION_OFFER_DEACTIVATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"pricing:promotion:manage"})
    @PreAuthorize("hasAuthority('" + PricingPermissions.PROMOTION_MANAGE + "')")
    @Operation(operationId = "deactivatePromotionOffer", summary = "Deactivate Promotion Offer", description = """
                    Transitions an ACTIVE promotion offer to INACTIVE so its promo code is no longer accepted by \
                    applyPromotionOffer.
                    Use this tool to withdraw a live offer while keeping its history and rules; do not use \
                    deletePromotionEligibilityRule, which removes a single audience rule rather than the offer.
                    Preconditions: the offer must exist and currently be ACTIVE; DRAFT, INACTIVE, and EXPIRED offers \
                    cannot be deactivated.
                    Required inputs: id (UUID) as a path parameter; there is no request body.
                    Emits a PROMOTION_OFFER_DEACTIVATE event; the offer can later be re-enabled with \
                    activatePromotionOffer.
                    Returns 404 when the offer does not exist, and 422 when the offer is not currently ACTIVE.
                    """)
    @ApiResponse(responseCode = "200", description = "Promotion offer deactivated.")
    @ApiResponse(responseCode = "404", description = "Promotion offer not found.")
    @ApiResponse(responseCode = "422", description = "Promotion offer is not currently ACTIVE.")
    @ApiResponse(responseCode = "403", description = "Forbidden.")
    public ResponseEntity<PromotionOfferResponse> deactivateOffer(@PathVariable("id") UUID promotionOfferId) {
        var offer = promotionOfferService.deactivateOffer(promotionOfferId);
        return ResponseEntity.ok(PromotionOfferMapper.toResponse(offer));
    }

    @PostMapping("/apply")
    @EmitEvent(id = "PROMOTION_OFFER_APPLY", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"pricing:promotion:apply"})
    @PreAuthorize("hasAuthority('" + PricingPermissions.PROMOTION_APPLY + "')")
    @Operation(
            operationId = "applyPromotionOffer",
            summary = "Apply Promotion Offer During Estimate Pricing",
            description = """
                    Applies a promotion code to an estimate's pricing context and returns the discount adjustment and \
                    the adjusted total.
                    Use this tool at estimate time to price in a promotion; use evaluatePromotionEligibility instead \
                    for a dry-run check, because this operation consumes one usage against the offer's usageLimit.
                    Preconditions: the offer must be ACTIVE, the current date must fall within startDate and endDate, \
                    the context must pass the offer's eligibility rules, the usage limit must not be exhausted, and no \
                    promo code may already be applied because only one promotion is allowed per estimate.
                    Required inputs: promotionCode and estimateContext with estimateId, customerId, subtotal, and at \
                    least one line item; PERCENT_LABOR and PERCENT_PARTS both currently discount the full subtotal by \
                    the percentage, while FIXED_INVOICE subtracts the fixed discountValue.
                    Emits a PROMOTION_OFFER_APPLY event and atomically increments the offer's usage count.
                    Returns 400 with code PROMO_NOT_FOUND when the code is unknown, PROMO_NOT_APPLICABLE when the \
                    offer is inactive, outside its date range, ineligible for the context, or over its usage limit, \
                    and PROMO_MULTIPLE_NOT_ALLOWED when the estimate already carries an applied promo code.
                    """)
    @ApiResponse(responseCode = "200", description = "Promotion offer applied.")
    @ApiResponse(
            responseCode = "400",
            description =
                    "Invalid request, unknown promo code, promotion not applicable, or promotion already applied.")
    @ApiResponse(responseCode = "403", description = "Forbidden.")
    public ResponseEntity<ApplyPromotionResponse> applyPromotion(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Promotion code plus the estimate context (customer, line items, subtotal) it is applied against.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Apply to estimate",
                                                            value = APPLY_PROMOTION_EXAMPLE)))
                    @RequestBody
                    @Valid
                    ApplyPromotionRequest request) {
        return ResponseEntity.ok(promotionOfferService.applyPromotion(request));
    }
}
