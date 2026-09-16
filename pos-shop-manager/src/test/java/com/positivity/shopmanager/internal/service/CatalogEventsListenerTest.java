package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.shopmanager.internal.entity.ExtCatalogServiceReplica;
import com.positivity.shopmanager.internal.entity.ExtCatalogServiceSkillReplica;
import com.positivity.shopmanager.internal.repository.ExtCatalogServiceReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtCatalogServiceSkillReplicaRepository;
import com.positivity.shopmanager.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/** CAP-329: {@code catalog.service.updated} (v3) mirrors the service and replace-sets its skill requirement. */
class CatalogEventsListenerTest {

    private static final String EVENT_ID = "01990000-0000-7000-8000-000000000301";
    private static final UUID SERVICE_ID = UUID.fromString("0196cf6f-c8dd-7ee0-93e7-f48a5698a535");

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-16T12:00:00Z"), ZoneOffset.UTC);
    private final ProcessedEventRepository processedEventRepository = mock(ProcessedEventRepository.class);
    private final ExtCatalogServiceReplicaRepository serviceRepository = mock(ExtCatalogServiceReplicaRepository.class);
    private final ExtCatalogServiceSkillReplicaRepository skillRepository =
            mock(ExtCatalogServiceSkillReplicaRepository.class);

    private CatalogEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new CatalogEventsListener(
                clock,
                new ObjectMapper(),
                processedEventRepository,
                serviceRepository,
                skillRepository,
                mock(ObjectProvider.class));
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(serviceRepository.findById(any())).thenReturn(Optional.empty());
    }

    private static String serviceEvent(long version, String requirementFields) {
        return """
                {"eventId":"%s","eventType":"catalog.service.updated","aggregateVersion":%d,
                 "payload":{"serviceId":"%s","name":"Brake pad replacement, front","shortDescription":null,
                            "longDescription":null,"active":true,"createdAt":"2026-01-01T00:00:00Z",
                            "updatedAt":"2026-06-01T00:00:00Z","operationCode":"BRAKE-PAD-REPLACE-FRONT",
                            "operationCategory":"REPAIR","defaultLaborHours":1.5%s}}""".formatted(EVENT_ID, version, SERVICE_ID, requirementFields);
    }

    @Test
    @DisplayName("a v3 fact mirrors the service and its class-conditional requirement, replace-set")
    void v3FactUpsertsServiceAndReplacesSkills() {
        listener.onCatalogEvent(serviceEvent(5, """
                ,"requirementsConfiguredAt":"2026-06-01T00:00:00Z",
                 "requiredSkills":[{"skillId":"01960011-0000-7000-8000-000000000040","skillCode":"BRAKES-LIGHT","minGvwrClass":1,"maxGvwrClass":3},
                                   {"skillId":"01960011-0000-7000-8000-000000000041","skillCode":"BRAKES-MEDIUM_HEAVY","minGvwrClass":4,"maxGvwrClass":8}]"""));

        ArgumentCaptor<ExtCatalogServiceReplica> service = ArgumentCaptor.forClass(ExtCatalogServiceReplica.class);
        verify(serviceRepository).save(service.capture());
        assertThat(service.getValue().getServiceId()).isEqualTo(SERVICE_ID);
        assertThat(service.getValue().getOperationCode()).isEqualTo("BRAKE-PAD-REPLACE-FRONT");
        assertThat(service.getValue().isActive()).isTrue();
        assertThat(service.getValue().isRequirementsConfigured()).isTrue();
        assertThat(service.getValue().getAggregateVersion()).isEqualTo(5);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExtCatalogServiceSkillReplica>> skills = ArgumentCaptor.forClass(List.class);
        verify(skillRepository).deleteAllByServiceId(SERVICE_ID);
        verify(skillRepository).saveAll(skills.capture());
        assertThat(skills.getValue())
                .extracting(
                        ExtCatalogServiceSkillReplica::getSkillCode,
                        ExtCatalogServiceSkillReplica::getMinGvwrClass,
                        ExtCatalogServiceSkillReplica::getMaxGvwrClass)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("BRAKES-LIGHT", 1, 3),
                        org.assertj.core.groups.Tuple.tuple("BRAKES-MEDIUM_HEAVY", 4, 8));
        assertThat(skills.getValue().getFirst().appliesTo(2)).isTrue();
        assertThat(skills.getValue().getFirst().appliesTo(4)).isFalse();
        assertThat(skills.getValue().getFirst().appliesTo(null)).isFalse();
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("an unconstrained declaration is a timestamp with no children — distinct from not configured")
    void unconstrainedDeclarationKeepsTheTimestamp() {
        listener.onCatalogEvent(
                serviceEvent(6, ",\"requirementsConfiguredAt\":\"2026-06-01T00:00:00Z\",\"requiredSkills\":[]"));

        ArgumentCaptor<ExtCatalogServiceReplica> service = ArgumentCaptor.forClass(ExtCatalogServiceReplica.class);
        verify(serviceRepository).save(service.capture());
        assertThat(service.getValue().isRequirementsConfigured()).isTrue();
        verify(skillRepository).saveAll(List.of());
    }

    @Test
    @DisplayName("a pre-v3 fact lands as not configured with no children — which is exactly what it says")
    void preV3FactIsNotConfigured() {
        listener.onCatalogEvent(serviceEvent(1, ""));

        ArgumentCaptor<ExtCatalogServiceReplica> service = ArgumentCaptor.forClass(ExtCatalogServiceReplica.class);
        verify(serviceRepository).save(service.capture());
        assertThat(service.getValue().isRequirementsConfigured()).isFalse();
        verify(skillRepository).saveAll(List.of());
    }

    @Test
    @DisplayName("a stale snapshot never moves the replica backwards, but is marked processed")
    void staleFactIsSkipped() {
        when(serviceRepository.findById(SERVICE_ID))
                .thenReturn(Optional.of(ExtCatalogServiceReplica.builder()
                        .serviceId(SERVICE_ID)
                        .aggregateVersion(9)
                        .build()));

        listener.onCatalogEvent(serviceEvent(8, ""));

        verify(serviceRepository, never()).save(any());
        verify(skillRepository, never()).deleteAllByServiceId(any());
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("product facts on the topic are acknowledged and ignored")
    void productFactsAreIgnored() {
        listener.onCatalogEvent("""
                {"eventId":"%s","eventType":"catalog.product.updated","aggregateVersion":1,
                 "payload":{"productId":"0196cf6f-c8dd-7ee0-93e7-f48a5698a700"}}""".formatted(EVENT_ID));

        verify(serviceRepository, never()).save(any());
        verify(processedEventRepository).save(any());
    }
}
