package com.positivity.marketing.internal.service;

import com.positivity.marketing.internal.domain.CatalogFocusRef;
import com.positivity.marketing.internal.entity.Campaign;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Checks the cross-domain references a campaign carries before it is allowed out
 * (Story #1148).
 *
 * <p>{@code promotionOfferId} and {@code catalogFocusRef} point at aggregates in other
 * services and are stored with no foreign key, so nothing stops a campaign from being saved
 * against an offer that has since expired or a catalog reference that was mistyped. Both
 * mistakes are invisible until the message lands in a customer's inbox advertising a discount
 * that will not apply — which is why they are checked at schedule time, the last moment a
 * campaign can still be fixed.
 *
 * <p>Problems are returned rather than thrown so they join the rest of the campaign's
 * readiness list; a marketer fixing a campaign wants every problem at once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CampaignReferenceValidator {

    private final Clock clock;
    private final PromotionOfferPort promotionOfferPort;

    /** Everything wrong with this campaign's offer and catalog references, in message form. */
    public @NonNull List<String> problems(@NonNull Campaign campaign) {
        List<String> problems = new ArrayList<>();
        promotionOfferProblem(campaign.getPromotionOfferId()).ifPresent(problems::add);
        catalogFocusProblem(campaign.getCatalogFocusRef()).ifPresent(problems::add);
        return problems;
    }

    /**
     * The offer must exist and be running.
     *
     * <p>Status and calendar window are checked separately because pricing stores EXPIRED as a
     * status something has to set: an offer whose end date passed last week can still read
     * ACTIVE until that happens, and a campaign promoting it would be advertising nothing.
     *
     * <p>An unreachable pricing service blocks too, and deliberately so — but with a message
     * that says so, because "we could not check" and "this offer is wrong" send the marketer
     * to completely different places. Scheduling is retryable; a sent campaign is not.
     */
    private Optional<String> promotionOfferProblem(UUID promotionOfferId) {
        if (promotionOfferId == null) {
            return Optional.empty();
        }
        PromotionOfferPort.OfferLookup lookup = promotionOfferPort.findOffer(promotionOfferId);
        return switch (lookup.outcome()) {
            case NOT_FOUND -> Optional.of("promotion offer " + promotionOfferId + " does not exist");
            case UNAVAILABLE ->
                Optional.of("promotion offer " + promotionOfferId + " could not be verified; pricing is unavailable");
            case FOUND -> activeOfferProblem(promotionOfferId, lookup.offer());
        };
    }

    private Optional<String> activeOfferProblem(UUID promotionOfferId, PromotionOfferPort.PromotionOffer offer) {
        if (offer == null) {
            return Optional.of(
                    "promotion offer " + promotionOfferId + " could not be verified; pricing is unavailable");
        }
        if (!offer.isActive()) {
            return Optional.of("promotion offer " + promotionOfferId + " is "
                    + (offer.status() == null ? "in an unknown status" : offer.status()) + ", not ACTIVE");
        }
        // Offers run on calendar days and carry no zone; UTC is the only defensible reading of
        // "today" for a service that schedules across regions.
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (!offer.runsOn(today)) {
            return Optional.of("promotion offer " + promotionOfferId + " is ACTIVE but its window ("
                    + describeWindow(offer) + ") does not include today");
        }
        return Optional.empty();
    }

    private static String describeWindow(PromotionOfferPort.PromotionOffer offer) {
        return (offer.startDate() == null ? "open" : offer.startDate().toString()) + " to "
                + (offer.endDate() == null ? "open" : offer.endDate().toString());
    }

    /**
     * The catalog reference must be one this platform can interpret.
     *
     * <p>Only the grammar is checked. Resolving the reference against the catalog itself needs
     * either a synchronous read across a domain wall or a replicated catalog fact, and neither
     * exists for this module: ADR-0044 permits no synchronous edge to the catalog domain, and
     * {@code catalog.events.v1} carries products only — nothing for the {@code service:} form
     * that campaigns most often point at. Grammar is what can be verified today, and it is
     * what catches the mistake actually made in practice: a marketer typing a bare name.
     */
    private Optional<String> catalogFocusProblem(String catalogFocusRef) {
        if (catalogFocusRef == null || catalogFocusRef.isBlank()) {
            return Optional.empty();
        }
        Optional<CatalogFocusRef> parsed = CatalogFocusRef.parse(catalogFocusRef);
        if (parsed.isEmpty()) {
            return Optional.of("catalogFocusRef '" + catalogFocusRef.trim()
                    + "' is not a catalog reference; write it as kind:value using one of "
                    + CatalogFocusRef.supportedKinds());
        }
        log.debug("Campaign catalog focus parsed as {}", parsed.get());
        return Optional.empty();
    }
}
