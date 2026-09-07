package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.entity.LaborTimeSourcePolicyEntity;
import com.positivity.catalog.internal.entity.ServiceEntity;
import com.positivity.catalog.internal.entity.ServiceLaborStandardEntity;
import com.positivity.catalog.internal.entity.ServiceOperationXrefEntity;
import com.positivity.catalog.internal.enums.LaborStandardOwnerScope;
import com.positivity.catalog.internal.enums.LaborTimeType;
import com.positivity.catalog.internal.enums.OperationCategory;
import com.positivity.catalog.internal.repository.LaborTimeSourcePolicyRepository;
import com.positivity.catalog.internal.repository.ServiceLaborStandardRepository;
import com.positivity.catalog.internal.repository.ServiceOperationXrefRepository;
import com.positivity.catalog.internal.repository.ServiceRepository;
import com.positivity.catalog.internal.service.LaborTimeResolution.MatchGrade;
import com.positivity.catalog.internal.service.LaborTimeResolution.Status;
import com.positivity.catalog.internal.spi.LaborTimeProviderPort;
import com.positivity.catalog.internal.spi.ProviderCallException;
import com.positivity.catalog.internal.spi.model.LaborTimeProviderDescriptor;
import com.positivity.catalog.internal.spi.model.LaborTimeProviderDescriptor.LicenseMode;
import com.positivity.catalog.internal.spi.model.ProviderLaborTime;
import com.positivity.catalog.internal.spi.model.VehicleKey;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Resolution semantics (#1569 Phase 1, sourcing plan §3.4): most specific stored match wins with
 * an honest grade, policy orders sources, live QUERY_ONLY answers are cached under their TTL,
 * default hours are last, and misses are typed — never thrown.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LaborTimeResolutionServiceImpl")
class LaborTimeResolutionServiceImplTest {

    private static final UUID SERVICE_ID = UUID.fromString("56b14899-cb6c-7628-0763-4c603ec0a325");
    private static final VehicleKey CIVIC = new VehicleKey("2019-2023", "Honda", "Civic", "EX", "K20C2");

    @Mock
    private ServiceLaborStandardRepository standardRepository;

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private ServiceOperationXrefRepository xrefRepository;

    @Mock
    private LaborTimeSourcePolicyRepository policyRepository;

    @Mock
    private LaborTimeProviderPort livePort;

    private LaborTimeResolutionServiceImpl service;

    @BeforeEach
    void setUp() {
        when(livePort.descriptor())
                .thenReturn(new LaborTimeProviderDescriptor(
                        "MOCKGUIDE_LIVE", "Mock guide live", LicenseMode.QUERY_ONLY, 110));
        service = new LaborTimeResolutionServiceImpl(
                standardRepository,
                serviceRepository,
                xrefRepository,
                policyRepository,
                Map.of("MOCKGUIDE_LIVE", livePort),
                Map.of("MOCKGUIDE_LIVE", Duration.ofMinutes(5)),
                Clock.fixed(Instant.parse("2026-09-02T08:00:00Z"), ZoneOffset.UTC));

        when(standardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(SERVICE_ID))
                .thenReturn(List.of());
        when(policyRepository.findByEnabledTrue()).thenReturn(List.of());
        when(xrefRepository.findBySourceCodeAndServiceId(any(), any())).thenReturn(Optional.empty());
        when(serviceRepository.findById(SERVICE_ID)).thenReturn(Optional.empty());
    }

    private static ServiceLaborStandardEntity row(
            String year, String make, String model, String submodel, String engine, String hours, String source) {
        ServiceLaborStandardEntity r = new ServiceLaborStandardEntity();
        r.setId(UUID.randomUUID());
        r.setServiceId(SERVICE_ID);
        r.setVehicleYear(year);
        r.setMake(make);
        r.setModel(model);
        r.setSubmodel(submodel);
        r.setEngineCode(engine);
        r.setLaborHours(new BigDecimal(hours));
        r.setTimeType(LaborTimeType.RETAIL_FLAT_RATE);
        r.setSourceCode(source);
        r.setSourceRevision("2026-09-01");
        return r;
    }

    private static final UUID SHOP_A = UUID.fromString("0198f2a1-0000-7000-8000-00000000000a");
    private static final UUID SHOP_B = UUID.fromString("0198f2a1-0000-7000-8000-00000000000b");

    private static ServiceLaborStandardEntity shopRow(UUID locationId, String make, String model, String hours) {
        ServiceLaborStandardEntity r = row(null, make, model, null, null, hours, "DURION");
        r.setOwnerScope(LaborStandardOwnerScope.SHOP);
        r.setOwnerLocationId(locationId);
        return r;
    }

    private static ServiceEntity serviceWithCategory(OperationCategory category) {
        ServiceEntity service = new ServiceEntity();
        service.setId(SERVICE_ID);
        service.setOperationCategory(category);
        return service;
    }

    private static LaborTimeSourcePolicyEntity policy(
            LaborTimeType timeType, String source, OperationCategory category, int precedence) {
        LaborTimeSourcePolicyEntity p = new LaborTimeSourcePolicyEntity();
        p.setId(UUID.randomUUID());
        p.setTimeType(timeType);
        p.setSourceCode(source);
        p.setOperationCategory(category);
        p.setPrecedence(precedence);
        p.setEnabled(true);
        return p;
    }

    @Nested
    @DisplayName("shop-owned times (#1575 Tier 0)")
    class ShopOwnership {

        @Test
        @DisplayName("the asking shop's own coarse time beats a platform row that matches the vehicle exactly")
        void shopRowOutranksMoreSpecificPlatformRow() {
            when(standardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(SERVICE_ID))
                    .thenReturn(List.of(
                            row("2019-2023", "Honda", "Civic", "EX", "K20C2", "1.5", "MOCKGUIDE"),
                            shopRow(SHOP_A, "Honda", "Civic", "2.3")));

            LaborTimeResolution resolution = service.resolve(SERVICE_ID, CIVIC, null, SHOP_A);

            assertThat(resolution.laborHours()).isEqualByComparingTo("2.3");
            assertThat(resolution.ownerScope()).isEqualTo("SHOP");
        }

        @Test
        @DisplayName("another shop's time is not a candidate at all — not even as a last resort")
        void otherShopsRowNeverAnswers() {
            when(standardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(SERVICE_ID))
                    .thenReturn(List.of(shopRow(SHOP_B, "Honda", "Civic", "2.3")));

            assertThat(service.resolve(SERVICE_ID, CIVIC, null, SHOP_A).status())
                    .isEqualTo(Status.NO_TIME_AVAILABLE);
        }

        @Test
        @DisplayName("a platform caller (no location) sees platform rows only")
        void platformCallerSeesPlatformRowsOnly() {
            when(standardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(SERVICE_ID))
                    .thenReturn(List.of(
                            shopRow(SHOP_A, "Honda", "Civic", "2.3"),
                            row(null, "Honda", "Civic", null, null, "2.0", "MOCKGUIDE")));

            LaborTimeResolution resolution = service.resolve(SERVICE_ID, CIVIC, null, null);

            assertThat(resolution.laborHours()).isEqualByComparingTo("2.0");
            assertThat(resolution.ownerScope()).isEqualTo("PLATFORM");
        }
    }

    @Nested
    @DisplayName("category-aware source precedence (#1569 R1)")
    class CategoryPrecedence {

        @Test
        @DisplayName("a policy row naming the operation's category beats the category-less row for the same pair")
        void categoryScopedPolicyWins() {
            when(serviceRepository.findById(SERVICE_ID))
                    .thenReturn(Optional.of(serviceWithCategory(OperationCategory.TIRE_SERVICE)));
            when(standardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(SERVICE_ID))
                    .thenReturn(List.of(
                            row(null, "Honda", "Civic", null, null, "2.0", "MOCKGUIDE"),
                            row(null, "Honda", "Civic", null, null, "1.2", "MICHELIN")));
            when(policyRepository.findByEnabledTrue())
                    .thenReturn(List.of(
                            policy(LaborTimeType.RETAIL_FLAT_RATE, "MOCKGUIDE", null, 100),
                            policy(LaborTimeType.RETAIL_FLAT_RATE, "MICHELIN", null, 200),
                            policy(LaborTimeType.RETAIL_FLAT_RATE, "MICHELIN", OperationCategory.TIRE_SERVICE, 10)));

            LaborTimeResolution resolution = service.resolve(SERVICE_ID, CIVIC, null, null);

            assertThat(resolution.sourceCode()).isEqualTo("MICHELIN");
            assertThat(resolution.laborHours()).isEqualByComparingTo("1.2");
        }

        @Test
        @DisplayName("the same policy set orders the other way for an operation outside that category")
        void categoryScopedPolicyDoesNotLeakToOtherCategories() {
            when(serviceRepository.findById(SERVICE_ID))
                    .thenReturn(Optional.of(serviceWithCategory(OperationCategory.REPAIR)));
            when(standardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(SERVICE_ID))
                    .thenReturn(List.of(
                            row(null, "Honda", "Civic", null, null, "2.0", "MOCKGUIDE"),
                            row(null, "Honda", "Civic", null, null, "1.2", "MICHELIN")));
            when(policyRepository.findByEnabledTrue())
                    .thenReturn(List.of(
                            policy(LaborTimeType.RETAIL_FLAT_RATE, "MOCKGUIDE", null, 100),
                            policy(LaborTimeType.RETAIL_FLAT_RATE, "MICHELIN", null, 200),
                            policy(LaborTimeType.RETAIL_FLAT_RATE, "MICHELIN", OperationCategory.TIRE_SERVICE, 10)));

            assertThat(service.resolve(SERVICE_ID, CIVIC, null, null).sourceCode())
                    .isEqualTo("MOCKGUIDE");
        }
    }

    @Nested
    @DisplayName("stored-row matching")
    class StoredMatching {

        @Test
        @DisplayName("the fully keyed row beats the model-level row, graded EXACT")
        void mostSpecificWins() {
            when(standardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(SERVICE_ID))
                    .thenReturn(List.of(
                            row(null, "Honda", "Civic", null, null, "2.0", "MOCKGUIDE"),
                            row("2019-2023", "Honda", "Civic", "EX", "K20C2", "1.5", "MOCKGUIDE")));

            LaborTimeResolution resolution = service.resolve(SERVICE_ID, CIVIC, null, null);

            assertThat(resolution.status()).isEqualTo(Status.RESOLVED);
            assertThat(resolution.laborHours()).isEqualByComparingTo("1.5");
            assertThat(resolution.matchGrade()).isEqualTo(MatchGrade.EXACT);
        }

        @Test
        @DisplayName("a row stating a field the request left unknown does not match")
        void statedFieldUnknownRequestNoMatch() {
            when(standardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(SERVICE_ID))
                    .thenReturn(List.of(row("2019-2023", "Honda", "Civic", null, "K20C2", "1.5", "MOCKGUIDE")));
            VehicleKey noEngine = new VehicleKey("2019-2023", "Honda", "Civic", null, null);

            LaborTimeResolution resolution = service.resolve(SERVICE_ID, noEngine, null, null);

            // The only row is engine-specific; a request that doesn't know the engine must not
            // receive it — with no default hours either, this is a clean typed miss.
            assertThat(resolution.status()).isEqualTo(Status.NO_TIME_AVAILABLE);
        }

        @Test
        @DisplayName("year+make+model with engine silent grades ENGINE_WILDCARD; wildcard-only grades MODEL_LEVEL")
        void gradesReported() {
            when(standardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(SERVICE_ID))
                    .thenReturn(List.of(row("2019-2023", "Honda", "Civic", null, null, "1.6", "MOCKGUIDE")));

            assertThat(service.resolve(SERVICE_ID, CIVIC, null, null).matchGrade())
                    .isEqualTo(MatchGrade.ENGINE_WILDCARD);

            when(standardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(SERVICE_ID))
                    .thenReturn(List.of(row(null, null, null, null, null, "1.0", "MOCKGUIDE")));

            assertThat(service.resolve(SERVICE_ID, CIVIC, null, null).matchGrade())
                    .isEqualTo(MatchGrade.MODEL_LEVEL);
        }

        @Test
        @DisplayName("at equal specificity the policy table orders sources — lower precedence wins")
        void policyOrdersSources() {
            ServiceLaborStandardEntity cheap = row("2019-2023", "Honda", "Civic", null, null, "1.4", "GUIDE_B");
            ServiceLaborStandardEntity preferred = row("2019-2023", "Honda", "Civic", null, null, "1.6", "GUIDE_A");
            when(standardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(SERVICE_ID))
                    .thenReturn(List.of(cheap, preferred));
            LaborTimeSourcePolicyEntity a = new LaborTimeSourcePolicyEntity();
            a.setTimeType(LaborTimeType.RETAIL_FLAT_RATE);
            a.setSourceCode("GUIDE_A");
            a.setPrecedence(10);
            LaborTimeSourcePolicyEntity b = new LaborTimeSourcePolicyEntity();
            b.setTimeType(LaborTimeType.RETAIL_FLAT_RATE);
            b.setSourceCode("GUIDE_B");
            b.setPrecedence(20);
            when(policyRepository.findByEnabledTrue()).thenReturn(List.of(a, b));

            LaborTimeResolution resolution = service.resolve(SERVICE_ID, CIVIC, null, null);

            assertThat(resolution.sourceCode()).isEqualTo("GUIDE_A");
            assertThat(resolution.laborHours()).isEqualByComparingTo("1.6");
        }

        @Test
        @DisplayName("a warranty preference picks the OEM_WARRANTY row over the retail one")
        void preferredTimeTypeWins() {
            ServiceLaborStandardEntity retail = row("2019-2023", "Honda", "Civic", null, null, "1.5", "MOCKGUIDE");
            ServiceLaborStandardEntity warranty = row("2019-2023", "Honda", "Civic", null, null, "1.2", "MOCKGUIDE");
            warranty.setTimeType(LaborTimeType.OEM_WARRANTY);
            when(standardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(SERVICE_ID))
                    .thenReturn(List.of(retail, warranty));

            LaborTimeResolution resolution = service.resolve(SERVICE_ID, CIVIC, LaborTimeType.OEM_WARRANTY, null);

            assertThat(resolution.timeType()).isEqualTo("OEM_WARRANTY");
            assertThat(resolution.laborHours()).isEqualByComparingTo("1.2");
        }
    }

    @Nested
    @DisplayName("live QUERY_ONLY path")
    class LivePath {

        @BeforeEach
        void mapXref() {
            ServiceOperationXrefEntity xref = new ServiceOperationXrefEntity();
            xref.setServiceId(SERVICE_ID);
            xref.setSourceCode("MOCKGUIDE_LIVE");
            xref.setProviderOpCode("MG-BRAKE-PAD-FRONT");
            when(xrefRepository.findBySourceCodeAndServiceId("MOCKGUIDE_LIVE", SERVICE_ID))
                    .thenReturn(Optional.of(xref));
        }

        @Test
        @DisplayName("with nothing stored, a live answer resolves — and the TTL cache spares the second call")
        void liveAnswerCached() {
            when(livePort.getLaborTime(any(), any()))
                    .thenReturn(Optional.of(new ProviderLaborTime(
                            "MG-BRAKE-PAD-FRONT",
                            new BigDecimal("1.7"),
                            "RETAIL_FLAT_RATE",
                            List.of(),
                            null,
                            "2026-09-01",
                            null,
                            null)));

            LaborTimeResolution first = service.resolve(SERVICE_ID, CIVIC, null, null);
            LaborTimeResolution second = service.resolve(SERVICE_ID, CIVIC, null, null);

            assertThat(first.status()).isEqualTo(Status.RESOLVED);
            assertThat(first.sourceCode()).isEqualTo("MOCKGUIDE_LIVE");
            assertThat(second.laborHours()).isEqualByComparingTo("1.7");
            verify(livePort, times(1)).getLaborTime(any(), any());
        }

        @Test
        @DisplayName("the cache honours the TTL against the injected clock: past expiry the provider is asked again")
        void cacheExpiresAgainstInjectedClock() {
            SteppableClock steppable = new SteppableClock(Instant.parse("2026-09-02T08:00:00Z"));
            service = new LaborTimeResolutionServiceImpl(
                    standardRepository,
                    serviceRepository,
                    xrefRepository,
                    policyRepository,
                    Map.of("MOCKGUIDE_LIVE", livePort),
                    Map.of("MOCKGUIDE_LIVE", Duration.ofMinutes(5)),
                    steppable);
            when(livePort.getLaborTime(any(), any()))
                    .thenReturn(Optional.of(new ProviderLaborTime(
                            "MG-BRAKE-PAD-FRONT",
                            new BigDecimal("1.7"),
                            "RETAIL_FLAT_RATE",
                            List.of(),
                            null,
                            "2026-09-01",
                            null,
                            null)));

            // Two calls inside the TTL: one provider hit.
            service.resolve(SERVICE_ID, CIVIC, null, null);
            steppable.advance(Duration.ofMinutes(4));
            service.resolve(SERVICE_ID, CIVIC, null, null);
            verify(livePort, times(1)).getLaborTime(any(), any());

            // Step past the 5-minute TTL: the cached answer's expiresAt is behind the clock, so
            // the provider must be consulted again — a license window is not stretchable.
            steppable.advance(Duration.ofMinutes(2));
            LaborTimeResolution refreshed = service.resolve(SERVICE_ID, CIVIC, null, null);
            verify(livePort, times(2)).getLaborTime(any(), any());
            assertThat(refreshed.status()).isEqualTo(Status.RESOLVED);
        }

        @Test
        @DisplayName("a failed live source degrades to default hours when the service has them")
        void liveFailureFallsBackToDefaultHours() {
            when(livePort.getLaborTime(any(), any())).thenThrow(new ProviderCallException("down"));
            ServiceEntity svc = new ServiceEntity();
            svc.setId(SERVICE_ID);
            svc.setDefaultLaborHours(new BigDecimal("2.0"));
            when(serviceRepository.findById(SERVICE_ID)).thenReturn(Optional.of(svc));

            LaborTimeResolution resolution = service.resolve(SERVICE_ID, CIVIC, null, null);

            assertThat(resolution.status()).isEqualTo(Status.RESOLVED);
            assertThat(resolution.matchGrade()).isEqualTo(MatchGrade.DEFAULT_HOURS);
            assertThat(resolution.sourceCode()).isEqualTo("DURION");
        }

        @Test
        @DisplayName("a failed live source with nothing else is SOURCE_UNAVAILABLE, not NO_TIME_AVAILABLE")
        void liveFailureNoFallbackIsSourceUnavailable() {
            when(livePort.getLaborTime(any(), any())).thenThrow(new ProviderCallException("down"));

            LaborTimeResolution resolution = service.resolve(SERVICE_ID, CIVIC, null, null);

            assertThat(resolution.status()).isEqualTo(Status.SOURCE_UNAVAILABLE);
        }
    }

    /** A clock the test steps forward by hand, so TTL expiry is deterministic. */
    private static final class SteppableClock extends Clock {
        private Instant now;

        private SteppableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Nested
    @DisplayName("fallbacks")
    class Fallbacks {

        @Test
        @DisplayName("default hours answer when no row matches, graded honestly as DEFAULT_HOURS")
        void defaultHoursAnswer() {
            ServiceEntity svc = new ServiceEntity();
            svc.setId(SERVICE_ID);
            svc.setDefaultLaborHours(new BigDecimal("1.0"));
            when(serviceRepository.findById(SERVICE_ID)).thenReturn(Optional.of(svc));

            LaborTimeResolution resolution = service.resolve(SERVICE_ID, VehicleKey.any(), null, null);

            assertThat(resolution.status()).isEqualTo(Status.RESOLVED);
            assertThat(resolution.laborHours()).isEqualByComparingTo("1.0");
            assertThat(resolution.matchGrade()).isEqualTo(MatchGrade.DEFAULT_HOURS);
        }

        @Test
        @DisplayName("nothing stored, nothing live, no default — a clean typed NO_TIME_AVAILABLE")
        void cleanMiss() {
            assertThat(service.resolve(SERVICE_ID, VehicleKey.any(), null, null).status())
                    .isEqualTo(Status.NO_TIME_AVAILABLE);
        }
    }
}
