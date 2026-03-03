package com.positivity.price.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.price.internal.dto.ApplyPromotionRequest;
import com.positivity.price.internal.dto.ApplyPromotionResponse;
import com.positivity.price.internal.dto.CreatePromotionOfferRequest;
import com.positivity.price.internal.dto.PromotionOfferMapper;
import com.positivity.price.internal.dto.PromotionOfferResponse;
import com.positivity.price.service.PromotionOfferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
@SecurityRequirement(name = "BearerAuth")
public class PromotionOfferController {

    private final PromotionOfferService promotionOfferService;

    public PromotionOfferController(PromotionOfferService promotionOfferService) {
        this.promotionOfferService = promotionOfferService;
    }

    @PostMapping
    @EmitEvent(id = "PROMOTION_OFFER_CREATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('Promotion:Manage')")
    public ResponseEntity<PromotionOfferResponse> createOffer(@Valid @RequestBody CreatePromotionOfferRequest request) {
        var offer = promotionOfferService.createOffer(request);
        var response = PromotionOfferMapper.toResponse(offer);
        URI location = URI.create("/v1/promotions/offers/" + response.getPromotionOfferId());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('Promotion:View')")
    public ResponseEntity<PromotionOfferResponse> getOfferById(@PathVariable("id") UUID promotionOfferId) {
        var offer = promotionOfferService.getOfferById(promotionOfferId);
        return ResponseEntity.ok(PromotionOfferMapper.toResponse(offer));
    }

    @GetMapping("/by-code/{promoCode}")
    @PreAuthorize("hasAuthority('Promotion:View')")
    public ResponseEntity<PromotionOfferResponse> getOfferByCode(@PathVariable("promoCode") String promoCode) {
        var offer = promotionOfferService.getOfferByCode(promoCode);
        return ResponseEntity.ok(PromotionOfferMapper.toResponse(offer));
    }

    @PatchMapping("/{id}/activate")
    @EmitEvent(id = "PROMOTION_OFFER_ACTIVATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('Promotion:Manage')")
    public ResponseEntity<PromotionOfferResponse> activateOffer(@PathVariable("id") UUID promotionOfferId) {
        var offer = promotionOfferService.activateOffer(promotionOfferId);
        return ResponseEntity.ok(PromotionOfferMapper.toResponse(offer));
    }

    @PatchMapping("/{id}/deactivate")
    @EmitEvent(id = "PROMOTION_OFFER_DEACTIVATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('Promotion:Manage')")
    public ResponseEntity<PromotionOfferResponse> deactivateOffer(@PathVariable("id") UUID promotionOfferId) {
        var offer = promotionOfferService.deactivateOffer(promotionOfferId);
        return ResponseEntity.ok(PromotionOfferMapper.toResponse(offer));
    }

    @PostMapping("/apply")
    @EmitEvent(id = "PROMOTION_OFFER_APPLY", apiVersion = "1")
    @PreAuthorize("hasAuthority('Promotion:Apply')")
    @Operation(summary = "Apply promotion offer during estimate pricing")
    public ResponseEntity<ApplyPromotionResponse> applyPromotion(
            @RequestBody @Valid ApplyPromotionRequest request) {
        return ResponseEntity.ok(promotionOfferService.applyPromotion(request));
    }
}