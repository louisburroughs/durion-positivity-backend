package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.dto.LaborIntelligenceRow;
import com.positivity.workorder.internal.entity.ExtCatalogServiceReplica;
import com.positivity.workorder.internal.repository.ExtCatalogServiceReplicaRepository;
import com.positivity.workorder.internal.repository.LaborIntelligenceRepository;
import java.math.BigDecimal;
import java.util.List;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The Tier 4 feedback loop (#1575 Tier 0, T0-5): what a shop's finished work says about an
 * operation, never promoted automatically and never derived from a sample too thin to mean
 * anything.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LaborIntelligenceService")
class LaborIntelligenceServiceTest {

    private static final UUID SERVICE_ID = UUID.fromString("0198f2a1-2222-7000-8000-000000000001");
    private static final UUID OTHER_SERVICE_ID = UUID.fromString("0198f2a1-2222-7000-8000-000000000002");
    private static final UUID SHOP_A = UUID.fromString("0198f2a1-0000-7000-8000-00000000000a");
    private static final UUID SHOP_B = UUID.fromString("0198f2a1-0000-7000-8000-00000000000b");
    private static final UUID TECH_1 = UUID.fromString("0198f2a1-3333-7000-8000-000000000001");
    private static final UUID TECH_2 = UUID.fromString("0198f2a1-3333-7000-8000-000000000002");

    @Mock
    private LaborIntelligenceRepository laborIntelligenceRepository;

    @Mock
    private ExtCatalogServiceReplicaRepository catalogServiceReplicaRepository;

    private LaborIntelligenceService service;

    @BeforeEach
    void setUp() {
        service = new LaborIntelligenceService(laborIntelligenceRepository, catalogServiceReplicaRepository);
        ReflectionTestUtils.setField(service, "minSamples", 5);
        when(laborIntelligenceRepository.findLineTotals()).thenReturn(List.of());
        when(laborIntelligenceRepository.findLineTotalsByTechnician()).thenReturn(List.of());
        when(catalogServiceReplicaRepository.findAllById(any())).thenReturn(List.of());
    }

    /** {@code [workorderServiceId, serviceEntityId, locationId, guideHours, actualHours]} */
    private static Object[] line(UUID serviceId, UUID locationId, String guide, String actual) {
        return new Object[] {UUID.randomUUID(), serviceId, locationId, new BigDecimal(guide), new BigDecimal(actual)};
    }

    /** {@code [workorderServiceId, serviceEntityId, technicianId, actualHours]} */
    private static Object[] techLine(UUID lineId, UUID serviceId, UUID technicianId, String actual) {
        return new Object[] {lineId, serviceId, technicianId, new BigDecimal(actual)};
    }

    private static List<Object[]> lines(UUID serviceId, UUID locationId, String guide, String... actuals) {
        return java.util.Arrays.stream(actuals)
                .map(actual -> line(serviceId, locationId, guide, actual))
                .toList();
    }

    @Nested
    @DisplayName("the shop rollup")
    class ShopRollup {

        @Test
        @DisplayName("reports the median actual against the guide baseline it was quoted at")
        void reportsMedianAgainstBaseline() {
            when(laborIntelligenceRepository.findLineTotals())
                    .thenReturn(lines(SERVICE_ID, SHOP_A, "0.5", "0.3", "0.4", "0.4", "0.5", "0.9"));

            LaborIntelligenceRow row = service.operations(null, null, null).get(0);

            assertThat(row.sampleCount()).isEqualTo(5);
            assertThat(row.medianActualHours()).isEqualByComparingTo("0.4");
            assertThat(row.medianGuideHours()).isEqualByComparingTo("0.5");
            assertThat(row.varianceHours()).isEqualByComparingTo("-0.1");
            assertThat(row.variancePct()).isEqualByComparingTo("-20.0");
        }

        @Test
        @DisplayName("the mean rides beside the median, so one runaway job is visible rather than hidden")
        void meanRidesBesideTheMedian() {
            when(laborIntelligenceRepository.findLineTotals())
                    .thenReturn(lines(SERVICE_ID, SHOP_A, "0.5", "0.4", "0.4", "0.4", "0.4", "4.0"));

            LaborIntelligenceRow row = service.operations(null, null, null).get(0);

            assertThat(row.medianActualHours()).isEqualByComparingTo("0.4");
            assertThat(row.meanActualHours()).isEqualByComparingTo("1.1");
        }

        @Test
        @DisplayName("shops are reported separately, never pooled into one platform-wide number")
        void shopsAreNeverPooled() {
            List<Object[]> all = new java.util.ArrayList<>(lines(SERVICE_ID, SHOP_A, "0.5", "0.4", "0.4"));
            all.addAll(lines(SERVICE_ID, SHOP_B, "0.5", "1.2", "1.2"));
            when(laborIntelligenceRepository.findLineTotals()).thenReturn(all);

            List<LaborIntelligenceRow> rows = service.operations(null, null, null);

            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(LaborIntelligenceRow::locationId).containsExactlyInAnyOrder(SHOP_A, SHOP_B);
        }

        @Test
        @DisplayName("naming a location narrows to it")
        void locationNarrows() {
            List<Object[]> all = new java.util.ArrayList<>(lines(SERVICE_ID, SHOP_A, "0.5", "0.4"));
            all.addAll(lines(SERVICE_ID, SHOP_B, "0.5", "1.2"));
            when(laborIntelligenceRepository.findLineTotals()).thenReturn(all);

            assertThat(service.operations(null, SHOP_A, null))
                    .singleElement()
                    .extracting(LaborIntelligenceRow::locationId)
                    .isEqualTo(SHOP_A);
        }

        @Test
        @DisplayName("the widest variance sorts first — that is where a curator's attention belongs")
        void widestVarianceFirst() {
            List<Object[]> all = new java.util.ArrayList<>(lines(SERVICE_ID, SHOP_A, "0.5", "0.5"));
            all.addAll(lines(OTHER_SERVICE_ID, SHOP_A, "2.0", "3.5"));
            when(laborIntelligenceRepository.findLineTotals()).thenReturn(all);

            List<LaborIntelligenceRow> rows = service.operations(null, null, null);

            assertThat(rows.get(0).serviceId()).isEqualTo(OTHER_SERVICE_ID);
            assertThat(rows.get(0).varianceHours()).isEqualByComparingTo("1.5");
        }

        @Test
        @DisplayName("the operation code comes from the local catalog replica when it knows it")
        void operationCodeComesFromTheReplica() {
            when(laborIntelligenceRepository.findLineTotals()).thenReturn(lines(SERVICE_ID, SHOP_A, "0.5", "0.4"));
            when(catalogServiceReplicaRepository.findAllById(any()))
                    .thenReturn(List.of(ExtCatalogServiceReplica.builder()
                            .serviceId(SERVICE_ID)
                            .operationCode("TIRE-ROTATION")
                            .active(true)
                            .build()));

            assertThat(service.operations("TIRE-ROTATION", null, null))
                    .singleElement()
                    .extracting(LaborIntelligenceRow::operationCode)
                    .isEqualTo("TIRE-ROTATION");
        }

        @Test
        @DisplayName("nothing finished yet is an empty list, not a row of zeroes")
        void nothingFinishedIsEmpty() {
            assertThat(service.operations(null, null, null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the suggested standard")
    class Suggestion {

        @Test
        @DisplayName("is withheld below the sample floor — a median of three jobs is a rumour")
        void withheldBelowTheFloor() {
            when(laborIntelligenceRepository.findLineTotals())
                    .thenReturn(lines(SERVICE_ID, SHOP_A, "0.5", "0.4", "0.4", "0.4"));

            LaborIntelligenceRow row = service.operations(null, null, null).get(0);

            assertThat(row.sampleCount()).isEqualTo(3);
            assertThat(row.suggestedStandardHours()).isNull();
            // The measurement is still reported; only the recommendation is withheld.
            assertThat(row.medianActualHours()).isEqualByComparingTo("0.4");
        }

        @Test
        @DisplayName("is the median actual once the sample reaches the floor")
        void offeredAtTheFloor() {
            when(laborIntelligenceRepository.findLineTotals())
                    .thenReturn(lines(SERVICE_ID, SHOP_A, "0.5", "0.4", "0.4", "0.4", "0.4", "0.4"));

            assertThat(service.operations(null, null, null).get(0).suggestedStandardHours())
                    .isEqualByComparingTo("0.4");
        }

        @Test
        @DisplayName("a caller may raise the threshold")
        void callerMayRaiseTheThreshold() {
            when(laborIntelligenceRepository.findLineTotals())
                    .thenReturn(lines(SERVICE_ID, SHOP_A, "0.5", "0.4", "0.4", "0.4", "0.4", "0.4"));

            assertThat(service.operations(null, null, 10).get(0).suggestedStandardHours())
                    .isNull();
        }

        @Test
        @DisplayName("a caller may not lower it below the configured floor")
        void callerMayNotLowerTheFloor() {
            when(laborIntelligenceRepository.findLineTotals())
                    .thenReturn(lines(SERVICE_ID, SHOP_A, "0.5", "0.4", "0.4"));

            assertThat(service.operations(null, null, 1).get(0).suggestedStandardHours())
                    .isNull();
        }
    }

    @Nested
    @DisplayName("technician medians")
    class TechnicianMedians {

        @Test
        @DisplayName("a technician with enough sole-worked lines gets a median")
        void soleWorkedLinesCount() {
            when(laborIntelligenceRepository.findLineTotals())
                    .thenReturn(lines(SERVICE_ID, SHOP_A, "0.5", "0.4", "0.4", "0.4", "0.4", "0.4"));
            when(laborIntelligenceRepository.findLineTotalsByTechnician())
                    .thenReturn(List.of(
                            techLine(UUID.randomUUID(), SERVICE_ID, TECH_1, "0.3"),
                            techLine(UUID.randomUUID(), SERVICE_ID, TECH_1, "0.3"),
                            techLine(UUID.randomUUID(), SERVICE_ID, TECH_1, "0.3"),
                            techLine(UUID.randomUUID(), SERVICE_ID, TECH_1, "0.4"),
                            techLine(UUID.randomUUID(), SERVICE_ID, TECH_1, "0.4")));

            LaborIntelligenceRow row = service.operations(null, null, null).get(0);

            assertThat(row.technicianSampleCount()).isEqualTo(1);
            assertThat(row.fastestTechnicianMedianHours()).isEqualByComparingTo("0.3");
        }

        @Test
        @DisplayName("a line two technicians split counts for neither — it says nothing about either's speed")
        void splitLinesCountForNobody() {
            UUID splitLine = UUID.randomUUID();
            when(laborIntelligenceRepository.findLineTotals())
                    .thenReturn(lines(SERVICE_ID, SHOP_A, "0.5", "0.4", "0.4", "0.4", "0.4", "0.4"));
            when(laborIntelligenceRepository.findLineTotalsByTechnician())
                    .thenReturn(List.of(
                            techLine(splitLine, SERVICE_ID, TECH_1, "0.2"),
                            techLine(splitLine, SERVICE_ID, TECH_2, "0.2"),
                            techLine(UUID.randomUUID(), SERVICE_ID, TECH_1, "0.3"),
                            techLine(UUID.randomUUID(), SERVICE_ID, TECH_1, "0.3"),
                            techLine(UUID.randomUUID(), SERVICE_ID, TECH_1, "0.3"),
                            techLine(UUID.randomUUID(), SERVICE_ID, TECH_1, "0.3")));

            LaborIntelligenceRow row = service.operations(null, null, null).get(0);

            // TECH_1 has four sole lines, one short of the floor; the split line does not top it up.
            assertThat(row.technicianSampleCount()).isZero();
            assertThat(row.fastestTechnicianMedianHours()).isNull();
        }

        @Test
        @DisplayName("a technician below the sample floor gets no median of their own")
        void thinTechnicianSampleIsWithheld() {
            when(laborIntelligenceRepository.findLineTotals())
                    .thenReturn(lines(SERVICE_ID, SHOP_A, "0.5", "0.4", "0.4", "0.4", "0.4", "0.4"));
            when(laborIntelligenceRepository.findLineTotalsByTechnician())
                    .thenReturn(List.of(
                            techLine(UUID.randomUUID(), SERVICE_ID, TECH_1, "0.2"),
                            techLine(UUID.randomUUID(), SERVICE_ID, TECH_1, "0.2")));

            assertThat(service.operations(null, null, null).get(0).technicianSampleCount())
                    .isZero();
        }
    }
}
