package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.client.CatalogLaborTimeClient;
import com.positivity.workorder.internal.entity.ExtCatalogServiceReplica;
import com.positivity.workorder.internal.repository.ExtCatalogServiceReplicaRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guide-time lookup ordering (#1569, sourcing plan §6.3): edge first, replica default second,
 * empty third — and estimating never fails over a guide being unreachable.
 */
@DisplayName("LaborTimeDefaultingService")
class LaborTimeDefaultingServiceTest {

    private static final UUID SERVICE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID VEHICLE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

    private final CatalogLaborTimeClient client = mock(CatalogLaborTimeClient.class);
    private final ExtCatalogServiceReplicaRepository replicaRepository = mock(ExtCatalogServiceReplicaRepository.class);
    private final VehicleReferenceService vehicleReferenceService = mock(VehicleReferenceService.class);

    private LaborTimeDefaultingService service;

    @BeforeEach
    void setUp() {
        service = new LaborTimeDefaultingService(client, replicaRepository, vehicleReferenceService);
        when(vehicleReferenceService.resolve(any(), any()))
                .thenReturn(new VehicleReferenceService.VehicleReference(
                        "2021 Honda Civic", "VIN1", "2021", "Honda", "Civic"));
        when(client.resolveLaborTime(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(replicaRepository.findById(SERVICE_ID)).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("an edge answer wins and carries its provenance, with included codes comma-joined")
    void edgeAnswerWins() {
        when(client.resolveLaborTime(SERVICE_ID, "2021", "Honda", "Civic", null))
                .thenReturn(Optional.of(new CatalogLaborTimeClient.GuideTime(
                        new BigDecimal("1.5"),
                        "RETAIL_FLAT_RATE",
                        "MOCKGUIDE",
                        "2026-09-01",
                        "EXACT",
                        "WHEEL-OFF",
                        List.of("BRAKE-PAD-FRONT", "BRAKE-PAD-REAR"),
                        "PLATFORM")));

        var guide = service.lookupGuideTime(SERVICE_ID, CUSTOMER_ID, VEHICLE_ID, null)
                .orElseThrow();

        assertThat(guide.hours()).isEqualByComparingTo("1.5");
        assertThat(guide.sourceCode()).isEqualTo("MOCKGUIDE");
        assertThat(guide.matchGrade()).isEqualTo("EXACT");
        assertThat(guide.includedOpCodes()).isEqualTo("BRAKE-PAD-FRONT,BRAKE-PAD-REAR");
    }

    @Test
    @DisplayName("with the edge silent, the replica's default hours answer as DEFAULT_HOURS")
    void replicaDefaultAnswersWhenEdgeSilent() {
        when(replicaRepository.findById(SERVICE_ID)).thenReturn(Optional.of(replica(new BigDecimal("2.0"), true)));

        var guide = service.lookupGuideTime(SERVICE_ID, CUSTOMER_ID, VEHICLE_ID, null)
                .orElseThrow();

        assertThat(guide.hours()).isEqualByComparingTo("2.0");
        assertThat(guide.matchGrade()).isEqualTo("DEFAULT_HOURS");
        assertThat(guide.sourceCode()).isEqualTo("DURION");
    }

    @Test
    @DisplayName("a tombstoned (inactive) replica row deliberately answers nothing")
    void tombstonedReplicaAnswersNothing() {
        when(replicaRepository.findById(SERVICE_ID)).thenReturn(Optional.of(replica(new BigDecimal("2.0"), false)));

        assertThat(service.lookupGuideTime(SERVICE_ID, CUSTOMER_ID, VEHICLE_ID, null))
                .isEmpty();
    }

    @Test
    @DisplayName("nothing anywhere is a clean empty — the writer types the hours")
    void nothingAnywhereIsEmpty() {
        assertThat(service.lookupGuideTime(SERVICE_ID, CUSTOMER_ID, VEHICLE_ID, null))
                .isEmpty();
    }

    private static ExtCatalogServiceReplica replica(BigDecimal hours, boolean active) {
        return ExtCatalogServiceReplica.builder()
                .serviceId(SERVICE_ID)
                .defaultLaborHours(hours)
                .active(active)
                .aggregateVersion(1L)
                .updatedAt(Instant.parse("2026-09-01T00:00:00Z"))
                .build();
    }
}
