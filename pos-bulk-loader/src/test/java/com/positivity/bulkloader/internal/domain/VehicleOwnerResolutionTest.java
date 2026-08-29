package com.positivity.bulkloader.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

/**
 * Resolution of a vehicle's owner from business keys.
 *
 * <p>Getting this wrong is quiet: a fleet attached to the wrong company loads cleanly, reports
 * success, and is only noticed when someone goes looking for vehicles that are not there. So the
 * cases pinned here are mostly the ones where refusing is the right answer.
 */
@SuppressWarnings({"java:S100", "java:S1192"})
class VehicleOwnerResolutionTest {

    private static final String ACME_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01";
    private static final String OTHER_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02";

    private final VehicleLoaderStrategy strategy = new VehicleLoaderStrategy();

    /** Records what was asked for, so the memoization claim can be checked rather than assumed. */
    private static final class StubContext implements ResolutionContext {
        private final List<Map<String, Object>> parties;
        private final Map<String, Optional<?>> cache = new HashMap<>();
        private final List<String> requestedUris = new ArrayList<>();

        StubContext(List<Map<String, Object>> parties) {
            this.parties = parties;
        }

        @Override
        @NonNull
        public UUID jobLocationId() {
            return UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4aff");
        }

        @Override
        @NonNull
        @SuppressWarnings("unchecked")
        public <R> Optional<R> get(@NonNull String serviceId, @NonNull String uri, @NonNull Class<R> responseType) {
            requestedUris.add(uri);
            return (Optional<R>) Optional.of(Map.of("results", parties));
        }

        @Override
        @NonNull
        @SuppressWarnings("unchecked")
        public <R> Optional<R> memoize(@NonNull String cacheKey, @NonNull Supplier<Optional<R>> loader) {
            return (Optional<R>) cache.computeIfAbsent(cacheKey, _ -> loader.get());
        }
    }

    private static Map<String, Object> party(String partyId, String displayName, String legalName) {
        Map<String, Object> party = new HashMap<>();
        party.put("partyId", partyId);
        party.put("displayName", displayName);
        party.put("legalName", legalName);
        return party;
    }

    private static VehicleBulkRecord vehicle(String ownerType, String ownerName) {
        VehicleBulkRecord record = new VehicleBulkRecord();
        record.setVin("1HGBH41JXMN109186");
        record.setUnitNumber("UNIT-1");
        record.setDescription("Box truck");
        record.setOwnerType(ownerType);
        record.setOwnerName(ownerName);
        return record;
    }

    @Test
    void resolve_exactNameMatch_setsTheAccountId() {
        StubContext context = new StubContext(List.of(party(ACME_ID, "Acme Auto", "Acme Auto LLC")));

        VehicleBulkRecord resolved = strategy.resolve(vehicle("ORGANIZATION", "Acme Auto"), context);

        assertThat(resolved.getAccountId()).isEqualTo(ACME_ID);
        assertThat(strategy.validate(resolved)).isEmpty();
    }

    @Test
    void resolve_matchesOnLegalNameToo() {
        StubContext context = new StubContext(List.of(party(ACME_ID, "Acme Auto", "Acme Auto LLC")));

        assertThat(strategy.resolve(vehicle("ORGANIZATION", "Acme Auto LLC"), context)
                        .getAccountId())
                .isEqualTo(ACME_ID);
    }

    @Test
    void resolve_isCaseInsensitiveAndTrims() {
        StubContext context = new StubContext(List.of(party(ACME_ID, "Acme Auto", "Acme Auto LLC")));

        assertThat(strategy.resolve(vehicle("ORGANIZATION", "  acme auto  "), context)
                        .getAccountId())
                .isEqualTo(ACME_ID);
    }

    @Test
    void resolve_partialMatchIsNotAMatch() {
        // The browse endpoint matches on "contains", so a search for "Acme Auto" also returns
        // "Acme Auto Parts". Taking the first hit would attach the fleet to the wrong company.
        StubContext context = new StubContext(List.of(party(OTHER_ID, "Acme Auto Parts", "Acme Auto Parts Inc")));

        VehicleBulkRecord resolved = strategy.resolve(vehicle("ORGANIZATION", "Acme Auto"), context);

        assertThat(resolved.getAccountId()).isNull();
        assertThat(strategy.validate(resolved)).anyMatch(error -> error.contains("accountId is required"));
    }

    @Test
    void resolve_ambiguousExactMatchResolvesToNothing() {
        StubContext context = new StubContext(
                List.of(party(ACME_ID, "Acme Auto", "Acme Auto LLC"), party(OTHER_ID, "Acme Auto", "Acme Auto GmbH")));

        assertThat(strategy.resolve(vehicle("ORGANIZATION", "Acme Auto"), context)
                        .getAccountId())
                .isNull();
    }

    @Test
    void resolve_noMatchLeavesTheRowToFailValidation() {
        StubContext context = new StubContext(List.of());

        VehicleBulkRecord resolved = strategy.resolve(vehicle("ORGANIZATION", "Nobody"), context);

        assertThat(resolved.getAccountId()).isNull();
        assertThat(strategy.validate(resolved)).isNotEmpty();
    }

    @Test
    void resolve_existingAccountIdIsLeftAlone_andCostsNoLookup() {
        StubContext context = new StubContext(List.of(party(ACME_ID, "Acme Auto", "Acme Auto LLC")));
        VehicleBulkRecord record = vehicle("ORGANIZATION", "Acme Auto");
        record.setAccountId(OTHER_ID);

        assertThat(strategy.resolve(record, context).getAccountId()).isEqualTo(OTHER_ID);
        assertThat(context.requestedUris).isEmpty();
    }

    @Test
    void resolve_repeatedOwnerCostsOneLookup() {
        // A 329-row fixture names 70 owners; without memoization this is 329 calls.
        StubContext context = new StubContext(List.of(party(ACME_ID, "Acme Auto", "Acme Auto LLC")));

        strategy.resolve(vehicle("ORGANIZATION", "Acme Auto"), context);
        strategy.resolve(vehicle("ORGANIZATION", "Acme Auto"), context);
        strategy.resolve(vehicle("ORGANIZATION", "acme auto"), context);

        assertThat(context.requestedUris).hasSize(1);
    }

    @Test
    void resolve_encodesTheOwnerNameIntoTheQuery() {
        StubContext context = new StubContext(List.of());

        strategy.resolve(vehicle("ORGANIZATION", "Smith & Sons"), context);

        assertThat(context.requestedUris.getFirst())
                .startsWith("/v1/crm/accounts/parties?")
                .contains("partyType=ORGANIZATION")
                .doesNotContain("Smith & Sons");
    }

    @Test
    void resolve_withoutOwnerKeys_isANoOp() {
        StubContext context = new StubContext(List.of(party(ACME_ID, "Acme Auto", "Acme Auto LLC")));

        VehicleBulkRecord record = vehicle(null, null);

        assertThat(strategy.resolve(record, context).getAccountId()).isNull();
        assertThat(context.requestedUris).isEmpty();
    }
}
