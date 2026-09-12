package com.positivity.bulkloader.internal.domain;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Party lookups, for the packs whose files name a company rather than carrying its party id.
 *
 * <p>Held here for the same reason as {@link LocationResolutions}: two packs now name a customer
 * this way — a vehicle's owner and a fleet requirement set's account — and the matching rule below
 * is the part that must not be re-derived per strategy.
 *
 * <p>An exact name match is required, and an ambiguous name resolves to nothing rather than to the
 * first hit: the browse endpoint matches on "contains", so "Ace Auto" would also return "Ace Auto
 * Parts". Guessing between them attaches the row to the wrong company, which reads as a successful
 * load and is invisible until someone notices where the data went.
 */
@Slf4j
public final class CustomerResolutions {

    private static final String CUSTOMER_SERVICE_ID = "customer";

    /** One page big enough that an ambiguity is visible rather than paged away. */
    private static final int PARTY_PAGE_SIZE = 50;

    private CustomerResolutions() {}

    /**
     * The party id of the one party of this type whose display or legal name is exactly
     * {@code name}, or empty when none or more than one matches.
     *
     * @param subject what the caller is resolving, named in the warning a miss logs, so an
     *     operator reading it knows which file and column to look at
     */
    @NonNull
    public static Optional<String> partyId(
            @NonNull ResolutionContext context,
            @NonNull String partyType,
            @NonNull String name,
            @NonNull String subject) {

        String type = partyApiType(partyType.trim());
        String trimmed = name.trim();
        return context.memoize(
                "party:" + type + ':' + trimmed.toLowerCase(Locale.ROOT),
                () -> lookUpParty(context, type, trimmed, subject));
    }

    /**
     * The {@code partyType} pos-customer answers to, from the word a fixture file uses.
     *
     * <p>Files name an owner the way a person would — {@code INDIVIDUAL} or {@code ORGANIZATION}
     * ({@link VehicleBulkRecord#getOwnerType()}) — while pos-customer's {@code PartyType} enum is
     * {@code PERSON} / {@code COMMERCIAL} / {@code UNKNOWN}. Passing the file's word straight
     * through sent a value the query could not bind, so every lookup came back empty and the row
     * failed with "accountId is required (or an ownerType and ownerName that resolve to one)" — a
     * message about the data, for a fault in the caller. Anything already spelled the API's way is
     * passed through unchanged, so a file that knows the enum keeps working.
     */
    private static String partyApiType(String fileType) {
        return switch (fileType.toUpperCase(Locale.ROOT)) {
            case "INDIVIDUAL" -> "PERSON";
            case "ORGANIZATION" -> "COMMERCIAL";
            default -> fileType;
        };
    }

    private static Optional<String> lookUpParty(
            ResolutionContext context, String partyType, String name, String subject) {
        String uri = UriComponentsBuilder.fromPath("/v1/crm/accounts/parties")
                .queryParam("name", name)
                .queryParam("partyType", partyType)
                .queryParam("size", PARTY_PAGE_SIZE)
                .encode(StandardCharsets.UTF_8)
                .toUriString();

        List<Map<String, Object>> results = context.get(CUSTOMER_SERVICE_ID, uri, Map.class)
                .map(body -> asResults(body.get("results")))
                .orElseGet(List::of);

        List<String> exactMatches = results.stream()
                .filter(party -> namesMatch(party, name))
                .map(party -> asText(party.get("partyId")))
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (exactMatches.size() != 1) {
            log.warn(
                    "{} {} '{}' {} — the row will fail on its missing id",
                    subject,
                    partyType,
                    name,
                    exactMatches.isEmpty() ? "matched no party" : "matched " + exactMatches.size() + " parties");
            return Optional.empty();
        }
        return Optional.of(exactMatches.getFirst());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asResults(Object results) {
        return results instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static boolean namesMatch(Map<String, Object> party, String name) {
        return name.equalsIgnoreCase(asText(party.get("displayName")))
                || name.equalsIgnoreCase(asText(party.get("legalName")));
    }

    private static String asText(Object value) {
        return value == null ? null : value.toString();
    }
}
