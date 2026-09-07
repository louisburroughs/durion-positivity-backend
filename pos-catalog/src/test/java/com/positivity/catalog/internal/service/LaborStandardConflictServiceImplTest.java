package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.dto.LaborStandardConflictDto;
import com.positivity.catalog.internal.entity.ServiceEntity;
import com.positivity.catalog.internal.entity.ServiceLaborStandardEntity;
import com.positivity.catalog.internal.enums.LaborTimeType;
import com.positivity.catalog.internal.repository.ServiceLaborStandardRepository;
import com.positivity.catalog.internal.repository.ServiceRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Cross-source conflict surfacing (#1569 R2): overlapping vehicle keys from different sources
 * that disagree beyond a threshold, never a comparison across time types.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LaborStandardConflictServiceImpl")
class LaborStandardConflictServiceImplTest {

    private static final UUID SERVICE_ID = UUID.fromString("56b14899-cb6c-7628-0763-4c603ec0a325");
    private static final UUID OTHER_SERVICE_ID = UUID.fromString("56b14899-cb6c-7628-0763-4c603ec0a326");
    private static final BigDecimal THRESHOLD = new BigDecimal("0.3");

    @Mock
    private ServiceLaborStandardRepository standardRepository;

    @Mock
    private ServiceRepository serviceRepository;

    private LaborStandardConflictServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LaborStandardConflictServiceImpl(standardRepository, serviceRepository);
        when(standardRepository.findBySupersededAtIsNullOrderByServiceIdAsc()).thenReturn(List.of());
        ServiceEntity entity = new ServiceEntity();
        entity.setId(SERVICE_ID);
        entity.setOperationCode("TIRE-INSTALL-SET-4");
        when(serviceRepository.findById(any())).thenReturn(Optional.of(entity));
    }

    private static ServiceLaborStandardEntity row(
            UUID serviceId,
            String year,
            String make,
            String model,
            String hours,
            String source,
            LaborTimeType timeType) {
        ServiceLaborStandardEntity r = new ServiceLaborStandardEntity();
        r.setId(UUID.randomUUID());
        r.setServiceId(serviceId);
        r.setVehicleYear(year);
        r.setMake(make);
        r.setModel(model);
        r.setLaborHours(new BigDecimal(hours));
        r.setTimeType(timeType);
        r.setSourceCode(source);
        r.setSourceRevision("rev");
        return r;
    }

    @Test
    @DisplayName("a wildcard row and a vehicle-keyed row from different sources overlap and are reported")
    void overlappingKeysFromDifferentSourcesConflict() {
        when(standardRepository.findBySupersededAtIsNullOrderByServiceIdAsc())
                .thenReturn(List.of(
                        row(SERVICE_ID, null, null, null, "1.1", "MICHELIN", LaborTimeType.MANUFACTURER_INSTALL),
                        row(
                                SERVICE_ID,
                                "2019",
                                "Honda",
                                "Civic",
                                "1.6",
                                "MOCKGUIDE",
                                LaborTimeType.MANUFACTURER_INSTALL)));

        List<LaborStandardConflictDto> conflicts = service.findConflicts(THRESHOLD);

        assertThat(conflicts).hasSize(1);
        LaborStandardConflictDto conflict = conflicts.get(0);
        assertThat(conflict.getDifferenceHours()).isEqualByComparingTo("0.5");
        assertThat(conflict.getOperationCode()).isEqualTo("TIRE-INSTALL-SET-4");
        assertThat(conflict.getTimeType()).isEqualTo("MANUFACTURER_INSTALL");
        // The narrower key is reported: it names the vehicles where the disagreement bites.
        assertThat(conflict.getVehicleKey()).isEqualTo("2019|Honda|Civic|*|*");
    }

    @Test
    @DisplayName("rows describing different vehicles never both answer, so they are not a conflict")
    void nonOverlappingKeysAreNotAConflict() {
        when(standardRepository.findBySupersededAtIsNullOrderByServiceIdAsc())
                .thenReturn(List.of(
                        row(SERVICE_ID, "2019", "Honda", "Civic", "1.1", "MICHELIN", LaborTimeType.RETAIL_FLAT_RATE),
                        row(
                                SERVICE_ID,
                                "2019",
                                "Toyota",
                                "Camry",
                                "1.9",
                                "MOCKGUIDE",
                                LaborTimeType.RETAIL_FLAT_RATE)));

        assertThat(service.findConflicts(THRESHOLD)).isEmpty();
    }

    @Test
    @DisplayName("warranty time is meant to differ from retail time — time types are never compared")
    void differentTimeTypesAreNeverCompared() {
        when(standardRepository.findBySupersededAtIsNullOrderByServiceIdAsc())
                .thenReturn(List.of(
                        row(SERVICE_ID, null, null, null, "0.8", "MOCKGUIDE", LaborTimeType.OEM_WARRANTY),
                        row(SERVICE_ID, null, null, null, "1.6", "MICHELIN", LaborTimeType.RETAIL_FLAT_RATE)));

        assertThat(service.findConflicts(THRESHOLD)).isEmpty();
    }

    @Test
    @DisplayName("one source disagreeing with itself is a supersession problem, not a source conflict")
    void sameSourceIsNotAConflict() {
        when(standardRepository.findBySupersededAtIsNullOrderByServiceIdAsc())
                .thenReturn(List.of(
                        row(SERVICE_ID, null, null, null, "1.1", "MOCKGUIDE", LaborTimeType.RETAIL_FLAT_RATE),
                        row(SERVICE_ID, "2019", "Honda", "Civic", "1.9", "mockguide", LaborTimeType.RETAIL_FLAT_RATE)));

        assertThat(service.findConflicts(THRESHOLD)).isEmpty();
    }

    @Test
    @DisplayName("a difference at exactly the threshold is agreement; the threshold is exclusive")
    void thresholdIsExclusive() {
        when(standardRepository.findBySupersededAtIsNullOrderByServiceIdAsc())
                .thenReturn(List.of(
                        row(SERVICE_ID, null, null, null, "1.1", "MICHELIN", LaborTimeType.RETAIL_FLAT_RATE),
                        row(SERVICE_ID, null, null, null, "1.4", "MOCKGUIDE", LaborTimeType.RETAIL_FLAT_RATE)));

        assertThat(service.findConflicts(THRESHOLD)).isEmpty();
        assertThat(service.findConflicts(new BigDecimal("0.2"))).hasSize(1);
    }

    @Test
    @DisplayName("rows for different services are never compared, however far apart their times are")
    void differentServicesAreNeverCompared() {
        when(standardRepository.findBySupersededAtIsNullOrderByServiceIdAsc())
                .thenReturn(List.of(
                        row(SERVICE_ID, null, null, null, "0.5", "MICHELIN", LaborTimeType.RETAIL_FLAT_RATE),
                        row(OTHER_SERVICE_ID, null, null, null, "9.0", "MOCKGUIDE", LaborTimeType.RETAIL_FLAT_RATE)));

        assertThat(service.findConflicts(THRESHOLD)).isEmpty();
    }

    @Test
    @DisplayName("the widest disagreement is reported first — that is the one a curator should look at")
    void widestDisagreementFirst() {
        UUID third = UUID.fromString("56b14899-cb6c-7628-0763-4c603ec0a327");
        when(standardRepository.findBySupersededAtIsNullOrderByServiceIdAsc())
                .thenReturn(List.of(
                        row(SERVICE_ID, null, null, null, "1.0", "MICHELIN", LaborTimeType.RETAIL_FLAT_RATE),
                        row(SERVICE_ID, null, null, null, "1.5", "MOCKGUIDE", LaborTimeType.RETAIL_FLAT_RATE),
                        row(third, null, null, null, "1.0", "MICHELIN", LaborTimeType.RETAIL_FLAT_RATE),
                        row(third, null, null, null, "3.0", "MOCKGUIDE", LaborTimeType.RETAIL_FLAT_RATE)));

        List<LaborStandardConflictDto> conflicts = service.findConflicts(THRESHOLD);

        assertThat(conflicts).hasSize(2);
        assertThat(conflicts.get(0).getDifferenceHours()).isEqualByComparingTo("2.0");
        assertThat(conflicts.get(1).getDifferenceHours()).isEqualByComparingTo("0.5");
    }
}
