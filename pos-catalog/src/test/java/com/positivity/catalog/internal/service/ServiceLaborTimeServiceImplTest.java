package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.enums.LaborTimeType;
import com.positivity.catalog.internal.spi.model.VehicleKey;
import com.positivity.catalog.service.model.LaborTimeQuoteRequest;
import com.positivity.catalog.service.model.LaborTimeQuoteResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The grant-surface adapter (#1569 Phase 1, ADR-0058 §5): all logic lives in the internal
 * resolution service; this class only translates contract records to internal types and back,
 * and a preference hint must never fail the caller's flow.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ServiceLaborTimeServiceImpl")
class ServiceLaborTimeServiceImplTest {

    private static final UUID SERVICE_ID = UUID.fromString("56b14899-cb6c-7628-0763-4c603ec0a325");

    @Mock
    private LaborTimeResolutionService resolutionService;

    private ServiceLaborTimeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ServiceLaborTimeServiceImpl(resolutionService);
        when(resolutionService.resolve(any(), any(), any(), any()))
                .thenReturn(LaborTimeResolution.miss(LaborTimeResolution.Status.NO_TIME_AVAILABLE));
    }

    @Test
    @DisplayName("delegates to the resolution service and maps every field of a resolved answer")
    void delegatesAndMapsResolvedAnswer() {
        when(resolutionService.resolve(eq(SERVICE_ID), any(), eq(LaborTimeType.OEM_WARRANTY), any()))
                .thenReturn(new LaborTimeResolution(
                        LaborTimeResolution.Status.RESOLVED,
                        new BigDecimal("1.5"),
                        "OEM_WARRANTY",
                        "MOCKGUIDE",
                        "2026-09-01",
                        LaborTimeResolution.MatchGrade.EXACT,
                        "WHEEL-OFF",
                        List.of("BRAKE-PAD-FRONT"),
                        "PLATFORM"));

        LaborTimeQuoteResponse response = service.resolveLaborTime(new LaborTimeQuoteRequest(
                SERVICE_ID, "2019-2023", "Honda", "Civic", "EX", "K20C2", "OEM_WARRANTY", null));

        assertThat(response.status()).isEqualTo(LaborTimeQuoteResponse.Status.RESOLVED);
        assertThat(response.laborHours()).isEqualByComparingTo("1.5");
        assertThat(response.timeType()).isEqualTo("OEM_WARRANTY");
        assertThat(response.sourceCode()).isEqualTo("MOCKGUIDE");
        assertThat(response.sourceRevision()).isEqualTo("2026-09-01");
        assertThat(response.matchGrade()).isEqualTo(LaborTimeQuoteResponse.MatchGrade.EXACT);
        assertThat(response.overlapGroup()).isEqualTo("WHEEL-OFF");
        assertThat(response.includedOpCodes()).containsExactly("BRAKE-PAD-FRONT");
        assertThat(response.ownerScope()).isEqualTo("PLATFORM");

        ArgumentCaptor<VehicleKey> vehicle = ArgumentCaptor.forClass(VehicleKey.class);
        verify(resolutionService).resolve(eq(SERVICE_ID), vehicle.capture(), eq(LaborTimeType.OEM_WARRANTY), eq(null));
        assertThat(vehicle.getValue()).isEqualTo(new VehicleKey("2019-2023", "Honda", "Civic", "EX", "K20C2"));
    }

    @Test
    @DisplayName("blank vehicle fields are trimmed to null — the null-as-wildcard convention")
    void blankVehicleFieldsTrimToNull() {
        service.resolveLaborTime(new LaborTimeQuoteRequest(SERVICE_ID, "  ", "Honda ", null, "", null, null, null));

        ArgumentCaptor<VehicleKey> vehicle = ArgumentCaptor.forClass(VehicleKey.class);
        verify(resolutionService).resolve(eq(SERVICE_ID), vehicle.capture(), eq(null), eq(null));
        assertThat(vehicle.getValue()).isEqualTo(new VehicleKey(null, "Honda", null, null, null));
    }

    @Test
    @DisplayName("an unknown preferredTimeType degrades to the default ordering, never errors")
    void unknownPreferenceDegradesToNull() {
        service.resolveLaborTime(
                new LaborTimeQuoteRequest(SERVICE_ID, null, null, null, null, null, "NOT_A_TYPE", null));

        verify(resolutionService).resolve(eq(SERVICE_ID), any(), eq(null), eq(null));
    }

    @Test
    @DisplayName("a typed miss maps status-for-status with no provenance invented")
    void missMapsStatusForStatus() {
        when(resolutionService.resolve(eq(SERVICE_ID), any(), any(), any()))
                .thenReturn(LaborTimeResolution.miss(LaborTimeResolution.Status.SOURCE_UNAVAILABLE));

        LaborTimeQuoteResponse response = service.resolveLaborTime(
                new LaborTimeQuoteRequest(SERVICE_ID, null, null, null, null, null, null, null));

        assertThat(response.status()).isEqualTo(LaborTimeQuoteResponse.Status.SOURCE_UNAVAILABLE);
        assertThat(response.laborHours()).isNull();
        assertThat(response.sourceCode()).isNull();
        assertThat(response.matchGrade()).isNull();
        assertThat(response.includedOpCodes()).isEmpty();
    }
}
