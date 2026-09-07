package com.positivity.bulkloader.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.bulkloader.internal.enums.DomainType;
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
 * The six Tier 0 loader domains (#1575; docs/DATA_SEED_STRATEGY.md §3 Tier 2).
 *
 * <p>Four of them carry only codes the owning service already knows, so the case that matters is
 * that a malformed value fails its row here rather than at the endpoint. The other two name a place
 * or a company, and there the case that matters is the one every name-keyed pack shares: a name
 * that resolves to nothing must fail its row rather than load a record pointing somewhere else — or,
 * worse for a fleet requirement set, load as an ordinary offering.
 */
@SuppressWarnings({"java:S100", "java:S1192"})
class Tier0PackStrategiesTest {

    private static final String SITE_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10";
    private static final String FLEET_PARTY_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a40";

    /** Serves the location roster and the party directory the two resolving packs read. */
    private static final class StubContext implements ResolutionContext {
        private final Map<String, Optional<?>> cache = new HashMap<>();
        final List<String> requestedUris = new ArrayList<>();

        @Override
        @NonNull
        public UUID jobLocationId() {
            return UUID.fromString(SITE_ID);
        }

        @Override
        @NonNull
        @SuppressWarnings("unchecked")
        public <R> Optional<R> get(@NonNull String serviceId, @NonNull String uri, @NonNull Class<R> responseType) {
            requestedUris.add(uri);
            if (uri.equals("/v1/locations")) {
                return (Optional<R>) Optional.of(List.of(Map.of("id", SITE_ID, "code", "CLT-MAIN-001")));
            }
            if (uri.contains("/crm/accounts/parties")) {
                return (Optional<R>) Optional.of(Map.of(
                        "results",
                        List.of(Map.of(
                                "partyId",
                                FLEET_PARTY_ID,
                                "legalName",
                                "Tarheel Logistics Group LLC",
                                "displayName",
                                "Tarheel Logistics"))));
            }
            return Optional.empty();
        }

        @Override
        @NonNull
        @SuppressWarnings("unchecked")
        public <R> Optional<R> memoize(@NonNull String cacheKey, @NonNull Supplier<Optional<R>> loader) {
            Optional<?> cached = cache.get(cacheKey);
            if (cached != null) {
                return (Optional<R>) cached;
            }
            Optional<R> loaded = loader.get();
            cache.put(cacheKey, loaded);
            return loaded;
        }
    }

    // ─── service operations ──────────────────────────────────────────────────

    @Test
    void services_acceptARowKeyedOnlyByItsOperationCode() {
        CatalogServiceLoaderStrategy strategy = new CatalogServiceLoaderStrategy();
        CatalogServiceLoaderRecord record = strategy.mapRow(Map.of(
                "operationCode",
                "TPMS-SENSOR-SERVICE",
                "name",
                "TPMS Service Kit - Set of 4",
                "operationCategory",
                "TIRE_SERVICE",
                "defaultLaborHours",
                "0.6"));

        assertThat(strategy.getDomainType()).isEqualTo(DomainType.CATALOG_SERVICE);
        assertThat(strategy.validate(record)).isEmpty();
        // Nothing to resolve: the code is the same key in every environment.
        StubContext context = new StubContext();
        assertThat(strategy.resolve(record, context)).isSameAs(record);
        assertThat(context.requestedUris).isEmpty();
    }

    @Test
    void services_rejectHoursThatAreNotANumber() {
        CatalogServiceLoaderStrategy strategy = new CatalogServiceLoaderStrategy();
        CatalogServiceLoaderRecord record = strategy.mapRow(Map.of(
                "operationCode", "LUG-TORQUE-RECHECK", "name", "Lug Torque Re-check", "defaultLaborHours", "a third"));

        assertThat(strategy.validate(record)).anyMatch(error -> error.contains("defaultLaborHours"));
    }

    // ─── labor standards ─────────────────────────────────────────────────────

    @Test
    void laborStandards_requireTheirProvenance() {
        ServiceLaborStandardLoaderStrategy strategy = new ServiceLaborStandardLoaderStrategy();
        ServiceLaborStandardLoaderRecord record =
                strategy.mapRow(Map.of("operationCode", "FLEET-PM-A-SERVICE", "laborHours", "1.4"));

        assertThat(strategy.validate(record))
                .anyMatch(error -> error.contains("sourceCode"))
                .anyMatch(error -> error.contains("sourceRevision"));
    }

    @Test
    void laborStandards_aShopRowResolvesItsSiteFromTheLocationCode() {
        ServiceLaborStandardLoaderStrategy strategy = new ServiceLaborStandardLoaderStrategy();
        ServiceLaborStandardLoaderRecord record = strategy.mapRow(Map.of(
                "operationCode", "FLEET-PM-A-SERVICE",
                "sourceCode", "DURION",
                "sourceRevision", "tier0-fake-2026-09",
                "laborHours", "1.4",
                "ownerScope", "SHOP",
                "ownerLocationCode", "CLT-MAIN-001"));

        ServiceLaborStandardLoaderRecord resolved = strategy.resolve(record, new StubContext());

        assertThat(resolved.getOwnerLocationId()).isEqualTo(SITE_ID);
        assertThat(strategy.validate(resolved)).isEmpty();
    }

    @Test
    void laborStandards_aShopRowWhoseSiteMatchedNothingFailsItsRow() {
        // A SHOP row with no location resolves for nobody, so it must not load as one that does.
        ServiceLaborStandardLoaderStrategy strategy = new ServiceLaborStandardLoaderStrategy();
        ServiceLaborStandardLoaderRecord record = strategy.mapRow(Map.of(
                "operationCode", "FLEET-PM-A-SERVICE",
                "sourceCode", "DURION",
                "sourceRevision", "tier0-fake-2026-09",
                "laborHours", "1.4",
                "ownerScope", "SHOP",
                "ownerLocationCode", "NOWHERE"));

        assertThat(strategy.validate(strategy.resolve(record, new StubContext())))
                .anyMatch(error -> error.contains("ownerLocationId is required"));
    }

    // ─── service packages ────────────────────────────────────────────────────

    @Test
    void packages_resolveTheirFleetFromTheCompanyName() {
        ServicePackageLoaderStrategy strategy = new ServicePackageLoaderStrategy();
        ServicePackageLoaderRecord record = strategy.mapRow(Map.of(
                "packageCode", "FLEET-REQ-TARHEEL",
                "name", "Tarheel Logistics - Standing Requirements",
                "packageLaborHours", "2.3",
                "fleetCustomerName", "Tarheel Logistics Group LLC"));

        ServicePackageLoaderRecord resolved = strategy.resolve(record, new StubContext());

        assertThat(resolved.getFleetPartyId()).isEqualTo(FLEET_PARTY_ID);
        assertThat(strategy.validate(resolved)).isEmpty();
    }

    @Test
    void packages_aFleetThatMatchedNothingFailsRatherThanLoadingAsAnOffering() {
        // Silently demoting a contracted requirement set to something a writer may decline is
        // exactly backwards, and it would load looking like a success.
        ServicePackageLoaderStrategy strategy = new ServicePackageLoaderStrategy();
        ServicePackageLoaderRecord record = strategy.mapRow(Map.of(
                "packageCode", "FLEET-REQ-TARHEEL",
                "name", "Tarheel Logistics - Standing Requirements",
                "packageLaborHours", "2.3",
                "fleetCustomerName", "A Company That Does Not Exist"));

        assertThat(strategy.validate(strategy.resolve(record, new StubContext())))
                .anyMatch(error -> error.contains("fleetPartyId is required"));
    }

    @Test
    void packages_anOfferingNeedsNoFleetAtAll() {
        ServicePackageLoaderStrategy strategy = new ServicePackageLoaderStrategy();
        ServicePackageLoaderRecord record = strategy.mapRow(Map.of(
                "packageCode", "TIRE-INSTALL-PKG-4",
                "name", "Four Tire Installation Package",
                "ownerScope", "PLATFORM",
                "packageLaborHours", "1.6"));

        StubContext context = new StubContext();
        assertThat(strategy.validate(strategy.resolve(record, context))).isEmpty();
        assertThat(context.requestedUris).isEmpty();
    }

    // ─── package membership ──────────────────────────────────────────────────

    @Test
    void members_needBothCodesAndNothingElse() {
        ServicePackageMemberLoaderStrategy strategy = new ServicePackageMemberLoaderStrategy();

        assertThat(strategy.validate(strategy.mapRow(Map.of(
                        "packageCode", "TIRE-INSTALL-PKG-4",
                        "operationCode", "WHEEL-BALANCE-SET-4"))))
                .isEmpty();
        assertThat(strategy.validate(strategy.mapRow(Map.of("packageCode", "TIRE-INSTALL-PKG-4"))))
                .anyMatch(error -> error.contains("operationCode"));
    }

    @Test
    void members_rejectASequenceThatIsNotAWholeNumber() {
        ServicePackageMemberLoaderStrategy strategy = new ServicePackageMemberLoaderStrategy();
        ServicePackageMemberLoaderRecord record = strategy.mapRow(Map.of(
                "packageCode", "TIRE-INSTALL-PKG-4",
                "operationCode", "WHEEL-BALANCE-SET-4",
                "sequence", "second"));

        assertThat(strategy.validate(record)).anyMatch(error -> error.contains("sequence"));
    }

    // ─── labor rates ─────────────────────────────────────────────────────────

    @Test
    void rates_aBlankLocationCodeIsThePlatformDefault_notAFailedLookup() {
        LaborRateLoaderStrategy strategy = new LaborRateLoaderStrategy();
        LaborRateLoaderRecord record = strategy.mapRow(Map.of(
                "locationCode",
                "",
                "currency",
                "USD",
                "hourlyRate",
                "125.0000",
                "effectiveFrom",
                "2026-01-01T00:00:00Z"));

        StubContext context = new StubContext();
        LaborRateLoaderRecord resolved = strategy.resolve(record, context);

        assertThat(resolved.getLocationId()).isNull();
        assertThat(strategy.validate(resolved)).isEmpty();
        assertThat(context.requestedUris).isEmpty();
    }

    @Test
    void rates_aNamedSiteResolves_andOneThatMatchedNothingFailsItsRow() {
        LaborRateLoaderStrategy strategy = new LaborRateLoaderStrategy();

        LaborRateLoaderRecord resolvable = strategy.resolve(
                strategy.mapRow(Map.of(
                        "locationCode",
                        "CLT-MAIN-001",
                        "currency",
                        "USD",
                        "hourlyRate",
                        "142.0000",
                        "effectiveFrom",
                        "2026-01-01T00:00:00Z")),
                new StubContext());
        assertThat(resolvable.getLocationId()).isEqualTo(SITE_ID);
        assertThat(strategy.validate(resolvable)).isEmpty();

        LaborRateLoaderRecord unresolvable = strategy.resolve(
                strategy.mapRow(Map.of(
                        "locationCode",
                        "NOWHERE",
                        "currency",
                        "USD",
                        "hourlyRate",
                        "142.0000",
                        "effectiveFrom",
                        "2026-01-01T00:00:00Z")),
                new StubContext());
        assertThat(strategy.validate(unresolvable)).anyMatch(error -> error.contains("locationId is required"));
    }

    @Test
    void rates_rejectAMalformedCurrency() {
        LaborRateLoaderStrategy strategy = new LaborRateLoaderStrategy();
        LaborRateLoaderRecord record = strategy.mapRow(
                Map.of("currency", "DOLLARS", "hourlyRate", "125.0000", "effectiveFrom", "2026-01-01T00:00:00Z"));

        assertThat(strategy.validate(record)).anyMatch(error -> error.contains("currency"));
    }

    // ─── labor matrix ────────────────────────────────────────────────────────

    @Test
    void adjustments_requireACodeATypeAValueAndASequence() {
        LaborRateAdjustmentLoaderStrategy strategy = new LaborRateAdjustmentLoaderStrategy();

        assertThat(strategy.validate(strategy.mapRow(Map.of("effectiveFrom", "2026-01-01T00:00:00Z"))))
                .anyMatch(error -> error.contains("adjustmentCode"))
                .anyMatch(error -> error.contains("adjustmentType"))
                .anyMatch(error -> error.contains("adjustmentValue"))
                .anyMatch(error -> error.contains("sequence"));
    }

    @Test
    void adjustments_acceptANegativeValue_becauseADiscountIsOne() {
        LaborRateAdjustmentLoaderStrategy strategy = new LaborRateAdjustmentLoaderStrategy();
        LaborRateAdjustmentLoaderRecord record = strategy.mapRow(Map.of(
                "adjustmentCode", "FLEET_CONTRACT",
                "adjustmentType", "PERCENT",
                "adjustmentValue", "-10.0000",
                "sequence", "90",
                "effectiveFrom", "2026-01-01T00:00:00Z"));

        assertThat(strategy.validate(record)).isEmpty();
        assertThat(strategy.getDomainType()).isEqualTo(DomainType.LABOR_RATE_ADJUSTMENT);
    }
}
