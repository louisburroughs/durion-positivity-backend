package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.location.internal.dto.BayTypeResponse;
import com.positivity.location.internal.entity.BaySpecialtyOperationEntity;
import com.positivity.location.internal.entity.ExtCatalogServiceReplica;
import com.positivity.location.internal.enums.BayType;
import com.positivity.location.internal.repository.BaySpecialtyOperationRepository;
import com.positivity.location.internal.repository.ExtCatalogServiceReplicaRepository;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BayTypeServiceImplTest {

    @Mock
    private BaySpecialtyOperationRepository operationRepository;

    @Mock
    private ExtCatalogServiceReplicaRepository replicaRepository;

    private BayTypeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BayTypeServiceImpl(operationRepository, replicaRepository);
    }

    @Test
    @DisplayName("one entry per BayType in enum order, each carrying the type's acceptsGeneralWork")
    void everyTypeInEnumOrder() {
        when(operationRepository.findAll()).thenReturn(List.of());

        List<BayTypeResponse> result = service.listBayTypes();

        assertThat(result).extracting(BayTypeResponse::getBayType).containsExactly(BayType.values());
        assertThat(result).allSatisfy(entry -> {
            assertThat(entry.isAcceptsGeneralWork())
                    .isEqualTo(entry.getBayType().acceptsGeneralWork());
            assertThat(entry.getDefaultServiceCapabilityCodes()).isEmpty();
            assertThat(entry.getDefaultServices()).isEmpty();
        });
        verify(replicaRepository, never()).findByOperationCodeInAndActiveIsTrue(any());
    }

    @Test
    @DisplayName("codes are normalized, de-duplicated and sorted; names come from the replica")
    void sortsAndNames() {
        when(operationRepository.findAll())
                .thenReturn(List.of(
                        row("TIRE_SERVICE", "tire-rotate "),
                        row("TIRE_SERVICE", "TIRE-BALANCE"),
                        row("TIRE_SERVICE", "TIRE-ROTATE"),
                        row("TIRE_SERVICE", " ")));
        activeServices(replica("TIRE-ROTATE", "Tire Rotation"), replica("TIRE-BALANCE", "Tire Balance"));

        BayTypeResponse tire = entryFor(service.listBayTypes(), BayType.TIRE_SERVICE);

        assertThat(tire.getDefaultServiceCapabilityCodes()).containsExactly("TIRE-BALANCE", "TIRE-ROTATE");
        assertThat(tire.getDefaultServices())
                .containsExactly(
                        new BayTypeResponse.DefaultServiceEntry("TIRE-BALANCE", "Tire Balance"),
                        new BayTypeResponse.DefaultServiceEntry("TIRE-ROTATE", "Tire Rotation"));
    }

    @Test
    @DisplayName("a seeded code that is retired or unknown in the replica is filtered out, not refused")
    void filtersInactiveCodes() {
        when(operationRepository.findAll())
                .thenReturn(List.of(
                        row("ALIGNMENT", "WHEEL-ALIGNMENT-4-WHEEL"),
                        row("ALIGNMENT", "RETIRED-ALIGN"),
                        row("INSPECTION", "NEVER-PUBLISHED")));
        activeServices(replica("WHEEL-ALIGNMENT-4-WHEEL", "4-Wheel Alignment"));

        List<BayTypeResponse> result = service.listBayTypes();

        assertThat(entryFor(result, BayType.ALIGNMENT).getDefaultServiceCapabilityCodes())
                .containsExactly("WHEEL-ALIGNMENT-4-WHEEL");
        assertThat(entryFor(result, BayType.INSPECTION).getDefaultServiceCapabilityCodes())
                .isEmpty();
        assertThat(entryFor(result, BayType.INSPECTION).getDefaultServices()).isEmpty();
    }

    @Test
    @DisplayName("rows naming a bay type the enum no longer has are ignored")
    void ignoresUnknownBayType() {
        when(operationRepository.findAll()).thenReturn(List.of(row("CAR_WASH", "WASH-EXTERIOR")));
        activeServices(replica("WASH-EXTERIOR", "Exterior Wash"));

        assertThat(service.listBayTypes())
                .hasSize(BayType.values().length)
                .allSatisfy(entry ->
                        assertThat(entry.getDefaultServiceCapabilityCodes()).isEmpty());
    }

    private void activeServices(ExtCatalogServiceReplica... services) {
        List<ExtCatalogServiceReplica> all = Arrays.asList(services);
        when(replicaRepository.findByOperationCodeInAndActiveIsTrue(any())).thenAnswer(invocation -> {
            Collection<String> codes = invocation.getArgument(0);
            return all.stream()
                    .filter(replica -> codes.contains(replica.getOperationCode()))
                    .toList();
        });
    }

    private static BayTypeResponse entryFor(List<BayTypeResponse> entries, BayType bayType) {
        return entries.stream()
                .filter(entry -> entry.getBayType() == bayType)
                .findFirst()
                .orElseThrow();
    }

    private static BaySpecialtyOperationEntity row(String bayType, String code) {
        return BaySpecialtyOperationEntity.builder()
                .id(UUID.randomUUID())
                .bayType(bayType)
                .operationCode(code)
                .build();
    }

    private static ExtCatalogServiceReplica replica(String code, String name) {
        return ExtCatalogServiceReplica.builder()
                .serviceId(UUID.randomUUID())
                .operationCode(code)
                .name(name)
                .active(true)
                .build();
    }
}
