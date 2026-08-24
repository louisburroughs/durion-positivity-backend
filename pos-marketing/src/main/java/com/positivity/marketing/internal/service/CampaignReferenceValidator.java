package com.positivity.marketing.internal.service;

import com.positivity.marketing.internal.domain.CatalogFocusRef;
import com.positivity.marketing.internal.entity.Campaign;
import com.positivity.marketing.internal.entity.ExtCatalogReplica;
import com.positivity.marketing.internal.enums.CatalogItemKind;
import com.positivity.marketing.internal.repository.ExtCatalogReplicaRepository;
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
    private final ExtCatalogReplicaRepository catalogReplicaRepository;

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
     * The catalog reference must name something that exists in the catalog and is still active.
     *
     * <p>Two checks, in order. The grammar check catches the mistake actually made in practice — a
     * marketer typing a bare name — and tells them what to write instead. Resolution then answers
     * the question grammar cannot: {@code service:alignment} is perfectly well-formed and still
     * points at nothing if nobody ever created that service, and the mistake surfaces when the
     * message lands in a customer's inbox advertising it.
     *
     * <p>Resolution reads the {@code ext_catalog} replica (#1306), never pos-catalog itself:
     * ADR-0044 R1 permits no synchronous edge into that domain.
     *
     * <p><b>Cold-replica behaviour, chosen deliberately: the check stands down for a kind the
     * replica holds no rows of at all, and blocks otherwise.</b> The replica is fed by
     * {@code catalog.events.v1} and is empty until that feed runs
     * ({@code POS_MARKETING_KAFKA_ENABLED} defaults to false), so resolving strictly against an
     * empty table would make every catalog reference a scheduling blocker in an environment that
     * never provisioned the feed — and, on a freshly deployed one, until pos-catalog's fact replays
     * ({@code POST /v1/products/facts/replay}, #1309, and {@code POST
     * /v1/catalog-items/services/facts/replay}, #1306) have been run. Both are operator actions
     * this module cannot perform or detect, and a check that fails until someone elsewhere runs a
     * command is a check nobody can satisfy from here.
     *
     * <p>Cold is judged per kind rather than over the whole table, because the two failures are
     * different: no rows of a kind means this module has never been told about that kind of catalog
     * item, while rows present and one missing means the reference is genuinely wrong. Seeding is
     * per kind too — the two replays are separate calls — so a table full of products says nothing
     * about whether the service half ever arrived. Standing down is logged at warn: the objection
     * to this choice is that the check quietly does nothing in exactly the case nobody notices, and
     * a log line naming the missing kind is what answers it.
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
        CatalogFocusRef reference = parsed.get();
        return switch (resolve(reference)) {
            case RESOLVED, NOT_REPLICATED -> Optional.empty();
            case RETIRED ->
                Optional.of("catalogFocusRef '" + reference + "' refers to a "
                        + reference.kind().wireName() + " that is no longer active in the catalog");
            case UNKNOWN -> Optional.of("catalogFocusRef '" + reference + "' is not known to this module yet");
        };
    }

    /** What the replica has to say about a reference. */
    private enum Resolution {
        RESOLVED,
        RETIRED,
        UNKNOWN,
        /** The replica holds nothing of this kind, so it cannot answer — see the note above. */
        NOT_REPLICATED
    }

    /**
     * Look the reference up by the kind it was written as.
     *
     * <p>{@code sku:} and {@code category:} resolve against product rows because that is what they
     * are — attributes of a product, not aggregates pos-catalog publishes facts about. A category
     * is therefore known here only through the products that carry it, which is the whole of what
     * the catalog says about it.
     */
    private Resolution resolve(CatalogFocusRef reference) {
        String value = reference.value();
        return switch (reference.kind()) {
            case PRODUCT -> classify(CatalogItemKind.PRODUCT, itemRows(CatalogItemKind.PRODUCT, value));
            case SERVICE -> classify(CatalogItemKind.SERVICE, itemRows(CatalogItemKind.SERVICE, value));
            case SKU -> classify(CatalogItemKind.PRODUCT, catalogReplicaRepository.findBySkuIgnoringCase(value));
            case CATEGORY ->
                classify(
                        CatalogItemKind.PRODUCT,
                        asUuid(value)
                                .map(catalogReplicaRepository::findByCategoryId)
                                .orElseGet(() -> catalogReplicaRepository.findByCategoryNameIgnoringCase(value)));
        };
    }

    /** A product or service is named either by id or by name; both are how references get written. */
    private List<ExtCatalogReplica> itemRows(CatalogItemKind kind, String value) {
        return asUuid(value)
                .map(id -> catalogReplicaRepository.findByItemKindAndCatalogItemId(kind, id))
                .orElseGet(() -> catalogReplicaRepository.findByKindAndNameIgnoringCase(kind, value));
    }

    /**
     * One active match is enough.
     *
     * <p>Names are not unique in pos-catalog, so a name can match several rows. A campaign pointing
     * at a name that resolves to two services is ambiguous but not wrong — it advertises something
     * that exists — whereas one whose every match has been retired advertises something that is
     * gone, which is the case worth stopping.
     *
     * <p>A miss is only a problem when the replica holds that kind of item at all; the count is
     * asked for only on a miss, so the ordinary answer costs one query.
     */
    private Resolution classify(CatalogItemKind kind, List<ExtCatalogReplica> rows) {
        if (!rows.isEmpty()) {
            return rows.stream().anyMatch(ExtCatalogReplica::isActive) ? Resolution.RESOLVED : Resolution.RETIRED;
        }
        if (catalogReplicaRepository.countByItemKind(kind) == 0) {
            log.warn(
                    "Catalog reference left unchecked: ext_catalog holds no {} rows, so the catalog feed has"
                            + " not reached this module. Campaign scheduling is not verifying catalog references.",
                    kind);
            return Resolution.NOT_REPLICATED;
        }
        return Resolution.UNKNOWN;
    }

    private static Optional<UUID> asUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
