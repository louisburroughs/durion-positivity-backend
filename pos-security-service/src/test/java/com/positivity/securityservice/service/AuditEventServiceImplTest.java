package com.positivity.securityservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.securityservice.internal.dto.AuditLogEventRequest;
import com.positivity.securityservice.internal.entity.AuditLogEvent;
import com.positivity.securityservice.internal.repository.AuditLogEventRepository;
import com.positivity.securityservice.internal.service.AuditEventServiceImpl;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditEventServiceImplTest {

    @Mock
    private AuditLogEventRepository auditLogEventRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private AuditEventServiceImpl auditEventService;

    @Test
    @DisplayName("createEvent stores immutable payload and returns mapped dto")
    void createEvent_success_returnsMappedDto() throws JsonProcessingException {
        AuditLogEventRequest request = new AuditLogEventRequest(
                "PRICING_OVERRIDE",
                "user-1",
                "quote-1",
                "QUOTE",
                Map.of("price", 100),
                Map.of("price", 95),
                Map.of("reason", "manager approval"));

        UUID eventId = UUID.randomUUID();
        Instant timestamp = Instant.parse("2026-02-21T10:00:00Z");

        when(objectMapper.writeValueAsString(request.getOldValue())).thenReturn("{\"price\":100}");
        when(objectMapper.writeValueAsString(request.getNewValue())).thenReturn("{\"price\":95}");
        when(objectMapper.writeValueAsString(request.getContext())).thenReturn("{\"reason\":\"manager approval\"}");
        when(auditLogEventRepository.save(any(AuditLogEvent.class))).thenAnswer(invocation -> {
            AuditLogEvent event = invocation.getArgument(0);
            event.setEventId(eventId);
            event.setTimestamp(timestamp);
            return event;
        });

        var result = auditEventService.createEvent(request);

        assertThat(result.getEventId()).isEqualTo(eventId);
        assertThat(result.getTimestamp()).isEqualTo(timestamp);
        assertThat(result.getEventType()).isEqualTo("PRICING_OVERRIDE");
        assertThat(result.getActorId()).isEqualTo("user-1");
        assertThat(result.getEntityId()).isEqualTo("quote-1");
        assertThat(result.getEntityType()).isEqualTo("QUOTE");
        assertThat(result.getOldValue()).isEqualTo("{\"price\":100}");
        assertThat(result.getNewValue()).isEqualTo("{\"price\":95}");
        assertThat(result.getContext()).isEqualTo("{\"reason\":\"manager approval\"}");

        verify(auditLogEventRepository).save(any(AuditLogEvent.class));
    }

    @Test
    @DisplayName("createEvent accepts null context")
    void createEvent_nullContext_persistsNullContext() throws JsonProcessingException {
        AuditLogEventRequest request = new AuditLogEventRequest(
                "PRICING_OVERRIDE",
                "user-1",
                "quote-1",
                "QUOTE",
                Map.of("price", 100),
                Map.of("price", 95),
                null);

        when(objectMapper.writeValueAsString(request.getOldValue())).thenReturn("{\"price\":100}");
        when(objectMapper.writeValueAsString(request.getNewValue())).thenReturn("{\"price\":95}");
        when(auditLogEventRepository.save(any(AuditLogEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = auditEventService.createEvent(request);

        assertThat(result.getContext()).isNull();
    }

    @Test
    @DisplayName("createEvent throws when required fields are missing")
    void createEvent_missingFields_throws() {
        AuditLogEventRequest request = new AuditLogEventRequest(null, "user-1", "quote-1", "QUOTE", Map.of(), Map.of(),
                null);

        assertThatThrownBy(() -> auditEventService.createEvent(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType is required");
    }

    @Test
    @DisplayName("createEvent throws when json serialization fails")
    void createEvent_jsonSerializationFailure_throws() throws JsonProcessingException {
        AuditLogEventRequest request = new AuditLogEventRequest(
                "PRICING_OVERRIDE",
                "user-1",
                "quote-1",
                "QUOTE",
                Map.of("price", 100),
                Map.of("price", 95),
                Map.of("reason", "invalid"));

        when(objectMapper.writeValueAsString(request.getOldValue())).thenThrow(new JsonProcessingException("bad json") {
        });

        assertThatThrownBy(() -> auditEventService.createEvent(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to serialize JSON field");
    }

    @Test
    @DisplayName("getEvent returns mapped dto when event exists")
    void getEvent_found_returnsDto() {
        UUID eventId = UUID.randomUUID();
        AuditLogEvent event = new AuditLogEvent();
        event.setEventId(eventId);
        event.setTimestamp(Instant.parse("2026-02-21T12:00:00Z"));
        event.setEventType("PRICING_OVERRIDE");
        event.setActorId("user-2");
        event.setEntityId("quote-2");
        event.setEntityType("QUOTE");
        event.setOldValue("{\"price\":120}");
        event.setNewValue("{\"price\":110}");
        event.setContext("{\"reason\":\"discount\"}");

        when(auditLogEventRepository.findById(eventId)).thenReturn(Optional.of(event));

        var result = auditEventService.getEvent(eventId);

        assertThat(result.getEventId()).isEqualTo(eventId);
        assertThat(result.getActorId()).isEqualTo("user-2");
    }

    @Test
    @DisplayName("getEvent throws when event does not exist")
    void getEvent_notFound_throws() {
        UUID eventId = UUID.randomUUID();
        when(auditLogEventRepository.findById(eventId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> auditEventService.getEvent(eventId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Audit event not found");
    }

    @Test
    @DisplayName("searchEvents uses range query when from and to are provided")
    void searchEvents_withRange_usesRangeRepositoryMethod() {
        Instant from = Instant.parse("2026-02-20T00:00:00Z");
        Instant to = Instant.parse("2026-02-22T00:00:00Z");

        AuditLogEvent event = new AuditLogEvent();
        event.setEventId(UUID.randomUUID());
        event.setTimestamp(Instant.parse("2026-02-21T13:00:00Z"));
        event.setEventType("PRICING_OVERRIDE");
        event.setActorId("user-3");
        event.setEntityId("quote-3");
        event.setEntityType("QUOTE");
        event.setOldValue("{\"price\":140}");
        event.setNewValue("{\"price\":130}");

        when(auditLogEventRepository.findByEntityIdAndEntityTypeAndTimestampBetweenOrderByTimestampDesc(
                "quote-3",
                "QUOTE",
                from,
                to)).thenReturn(List.of(event));

        var result = auditEventService.searchEvents("quote-3", "QUOTE", from, to);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEntityId()).isEqualTo("quote-3");
    }

    @Test
    @DisplayName("searchEvents uses non-range query when from or to is missing")
    void searchEvents_withoutRange_usesNonRangeRepositoryMethod() {
        AuditLogEvent event = new AuditLogEvent();
        event.setEventId(UUID.randomUUID());
        event.setTimestamp(Instant.parse("2026-02-21T14:00:00Z"));
        event.setEventType("PRICING_OVERRIDE");
        event.setActorId("user-4");
        event.setEntityId("quote-4");
        event.setEntityType("QUOTE");
        event.setOldValue("{\"price\":150}");
        event.setNewValue("{\"price\":145}");

        when(auditLogEventRepository.findByEntityIdAndEntityTypeOrderByTimestampDesc("quote-4", "QUOTE"))
                .thenReturn(List.of(event));

        var result = auditEventService.searchEvents("quote-4", "QUOTE", null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEntityType()).isEqualTo("QUOTE");
    }

    @Test
    @DisplayName("searchEvents throws when entity filters are missing")
    void searchEvents_missingFilters_throws() {
        assertThatThrownBy(() -> auditEventService.searchEvents(" ", "QUOTE", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityId and entityType are required");

        assertThatThrownBy(() -> auditEventService.searchEvents("quote-5", " ", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityId and entityType are required");
    }
}
