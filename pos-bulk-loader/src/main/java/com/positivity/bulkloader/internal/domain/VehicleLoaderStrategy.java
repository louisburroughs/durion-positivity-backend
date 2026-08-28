package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@Slf4j
public class VehicleLoaderStrategy implements DomainLoaderStrategy<VehicleBulkRecord> {

    private static final String CUSTOMER_SERVICE_ID = "customer";

    @Override
    public DomainType getDomainType() {
        return DomainType.VEHICLE;
    }

    @Override
    public VehicleBulkRecord mapRow(@NonNull Map<String, String> row) {
        VehicleBulkRecord vehicleBulkRecord = new VehicleBulkRecord();
        vehicleBulkRecord.setAccountId(row.get("accountId"));
        vehicleBulkRecord.setVin(row.get("vin"));
        vehicleBulkRecord.setUnitNumber(row.get("unitNumber"));
        vehicleBulkRecord.setDescription(row.get("description"));
        vehicleBulkRecord.setMake(row.get("make"));
        vehicleBulkRecord.setModel(row.get("model"));
        vehicleBulkRecord.setYear(row.get("year"));
        vehicleBulkRecord.setTrim(row.get("trim"));
        vehicleBulkRecord.setLicensePlate(row.get("licensePlate"));
        vehicleBulkRecord.setLicensePlateJurisdiction(row.get("licensePlateJurisdiction"));
        vehicleBulkRecord.setOwnerType(row.get("ownerType"));
        vehicleBulkRecord.setOwnerName(row.get("ownerName"));
        return vehicleBulkRecord;
    }

    /**
     * Turns {@code ownerType} + {@code ownerName} into the account id the vehicle ingest expects.
     *
     * <p>Rows that already carry an {@code accountId} are left alone, so a file that knows the id
     * still loads unchanged.
     *
     * <p>An exact name match is required, and an ambiguous name resolves to nothing rather than to
     * the first hit: the browse endpoint matches on "contains", so "Ace Auto" would also return
     * "Ace Auto Parts". Guessing between them attaches a fleet to the wrong company, which reads as
     * a successful load and is invisible until someone notices the vehicles are missing.
     */
    @Override
    @NonNull
    public VehicleBulkRecord resolve(@NonNull VehicleBulkRecord item, @NonNull ResolutionContext context) {
        if (isPresent(item.getAccountId()) || !isPresent(item.getOwnerName()) || !isPresent(item.getOwnerType())) {
            return item;
        }

        String ownerType = item.getOwnerType().trim();
        String ownerName = item.getOwnerName().trim();
        String cacheKey = "party:" + ownerType + ':' + ownerName.toLowerCase(Locale.ROOT);

        context.memoize(cacheKey, () -> lookUpParty(context, ownerType, ownerName))
                .ifPresent(item::setAccountId);
        return item;
    }

    private Optional<String> lookUpParty(ResolutionContext context, String ownerType, String ownerName) {
        String uri = UriComponentsBuilder.fromPath("/v1/crm/accounts/parties")
                .queryParam("name", ownerName)
                .queryParam("partyType", ownerType)
                .queryParam("size", 50)
                .encode(StandardCharsets.UTF_8)
                .toUriString();

        List<Map<String, Object>> results = context.get(CUSTOMER_SERVICE_ID, uri, Map.class)
                .map(body -> asResults(body.get("results")))
                .orElseGet(List::of);

        List<String> exactMatches = results.stream()
                .filter(party -> namesMatch(party, ownerName))
                .map(party -> asText(party.get("partyId")))
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (exactMatches.size() != 1) {
            log.warn(
                    "Vehicle owner {} '{}' {} — the row will fail on its missing accountId",
                    ownerType,
                    ownerName,
                    exactMatches.isEmpty() ? "matched no party" : "matched " + exactMatches.size() + " parties");
            return Optional.empty();
        }
        return Optional.of(exactMatches.getFirst());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asResults(Object results) {
        return results instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private boolean namesMatch(Map<String, Object> party, String ownerName) {
        return ownerName.equalsIgnoreCase(asText(party.get("displayName")))
                || ownerName.equalsIgnoreCase(asText(party.get("legalName")));
    }

    private String asText(Object value) {
        return value == null ? null : value.toString();
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public List<String> validate(@NonNull VehicleBulkRecord item) {
        List<String> errors = new ArrayList<>();
        if (item.getAccountId() == null || item.getAccountId().isBlank()) {
            // Names the resolvable alternative, because for a fixture-shaped file the operator
            // supplied ownerType/ownerName and the real fault is that they matched no party.
            errors.add("accountId is required (or an ownerType and ownerName that resolve to one)");
        } else {
            try {
                java.util.UUID.fromString(item.getAccountId());
            } catch (IllegalArgumentException _) {
                errors.add("accountId must be a valid UUID");
            }
        }
        if (item.getVin() == null || item.getVin().isBlank()) {
            errors.add("vin is required");
        } else if (item.getVin().trim().length() != 17) {
            errors.add("vin must be exactly 17 characters");
        }
        if (item.getUnitNumber() == null || item.getUnitNumber().isBlank()) {
            errors.add("unitNumber is required");
        }
        if (item.getDescription() == null || item.getDescription().isBlank()) {
            errors.add("description is required");
        }
        return errors;
    }
}
