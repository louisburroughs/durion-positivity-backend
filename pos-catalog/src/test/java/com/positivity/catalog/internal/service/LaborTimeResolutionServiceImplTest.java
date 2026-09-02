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
import com.positivity.catalog.internal.enums.LaborTimeType;
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

            LaborTimeResolution resolution = service.resolve(SERVICE_ID, CIVIC, null);

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

            LaborTimeResolution resolution = service.resolve(SERVICE_ID, noEngine, null);

            // The only row is engine-specific; a request that doesn't know the engine must not
            // receive it — with no default hours either, this is a clean typed miss.
            assertThat(resolution.status()).isEqualTo(Status.NO_TIME_AVAILABLE);
        }

        @Test
        @DisplayName("year+make+model with engine silent grades ENGINE_WILDCARD; wildcard-only grades MODEL_LEVEL")
        void gradesReported() {
            when(standardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(SERVICE_ID))
                    .thenReturn(List.of(row("2019-2023", "Honda", "Civic", null, null, "1.6", "MOCKGUIDE")));

            assertThat(service.resolve(SERVICE_ID, CIVIC, null).matchGrade()).isEqualTo(MatchGrade.ENGINE_WILDCARD);

            when(standardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(SERVICE_ID))
                    .thenReturn(List.of(row(null, null, null, null, null, "1.0", "MOCKGUIDE")));

            assertThat(service.resolve(SERVICE_ID, CIVIC, null).matchGrade()).isEqualTo(MatchGrade.MODEL_LEVEL);
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

            LaborTimeResolution resolution = service.resolve(SERVICE_ID, CIVIC, null);

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

            LaborTimeResolution resolution = service.resolve(SERVICE_ID, CIVIC, LaborTimeType.OEM_WARRANTY);

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

            LaborTimeResolution first = service.resolve(SERVICE_ID, CIVIC, null);
            LaborTimeResolution second = service.resolve(SERVICE_ID, CIVIC, null);

            assertThat(first.status()).isEqualTo(Status.RESOLVED);
            assertThat(first.sourceCode()).isEqualTo("MOCKGUIDE_LIVE");
            assertThat(second.laborHours()).isEqualByComparingTo("1.7");
            verify(livePort, times(1)).getLaborTime(any(), any());
        }

        @Test
        @DisplayName("a failed live source degrades to default hours when the service has them")
        void liveFailureFallsBackToDefaultHours() {
            when(livePort.getLaborTime(any(), any())).thenThrow(new ProviderCallException("down"));
            ServiceEntity svc = new ServiceEntity();
            svc.setId(SERVICE_ID);
            svc.setDefaultLaborHours(new BigDecimal("2.0"));
            when(serviceRepository.findById(SERVICE_ID)).thenReturn(Optional.of(svc));

            LaborTimeResolution resolution = service.resolve(SERVICE_ID, CIVIC, null);

            assertThat(resolution.status()).isEqualTo(Status.RESOLVED);
            assertThat(resolution.matchGrade()).isEqualTo(MatchGrade.DEFAULT_HOURS);
            assertThat(resolution.sourceCode()).isEqualTo("DURION");
        }

        @Test
        @DisplayName("a failed live source with nothing else is SOURCE_UNAVAILABLE, not NO_TIME_AVAILABLE")
        void liveFailureNoFallbackIsSourceUnavailable() {
            when(livePort.getLaborTime(any(), any())).thenThrow(new ProviderCallException("down"));

            LaborTimeResolution resolution = service.resolve(SERVICE_ID, CIVIC, null);

            assertThat(resolution.status()).isEqualTo(Status.SOURCE_UNAVAILABLE);
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

            LaborTimeResolution resolution = service.resolve(SERVICE_ID, VehicleKey.any(), null);

            assertThat(resolution.status()).isEqualTo(Status.RESOLVED);
            assertThat(resolution.laborHours()).isEqualByComparingTo("1.0");
            assertThat(resolution.matchGrade()).isEqualTo(MatchGrade.DEFAULT_HOURS);
        }

        @Test
        @DisplayName("nothing stored, nothing live, no default — a clean typed NO_TIME_AVAILABLE")
        void cleanMiss() {
            assertThat(service.resolve(SERVICE_ID, VehicleKey.any(), null).status())
                    .isEqualTo(Status.NO_TIME_AVAILABLE);
        }
    }
}
