package com.positivity.poseventreceiver.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.poseventreceiver.internal.service.EmitEventService;
import com.positivity.poseventreceiver.internal.service.EventQueryService;
import com.positivity.poseventreceiver.internal.service.EventTypeService;
import com.positivity.web.common.GlobalApiExceptionHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Pins the error body clients actually receive from this module's controllers (issue #1720).
 *
 * <p>These operations document their 4xx responses as the ApiError envelope. They used to answer
 * with {@code ResponseEntity.notFound().build()} / {@code badRequest().build()} — an empty body —
 * or, for {@code receiveEvent}, a plain-text string. The controllers now throw
 * {@code ResponseStatusException}, and pos-web-common's {@link GlobalApiExceptionHandler} (this
 * module has no advice of its own) renders it. Standalone MockMvc wires exactly that advice.
 */
@DisplayName("pos-event-receiver controllers - 4xx responses carry the ApiError envelope")
class EventReceiverErrorEnvelopeTest {

    private final EventTypeService eventTypeService = mock(EventTypeService.class);
    private final EventQueryService eventQueryService = mock(EventQueryService.class);
    private final EmitEventService emitEventService = mock(EmitEventService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-19T00:00:00Z"), ZoneOffset.UTC);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new EventTypeController(eventTypeService),
                        new EventQueryController(eventQueryService),
                        new EmitEventController(emitEventService))
                .setControllerAdvice(new GlobalApiExceptionHandler(clock))
                .build();
    }

    private static void assertEnvelope(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").value("2026-09-19T00:00:00Z"));
    }

    @Test
    @DisplayName("getEventTypeById: unknown id is a 404 ApiError")
    void getEventTypeById_notFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(eventTypeService.getEventTypeById(id)).thenReturn(Optional.empty());

        assertEnvelope(mockMvc.perform(get("/v1/eventTypes/{id}", id)), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("getEventTypeByCode: malformed code is a 400 ApiError")
    void getEventTypeByCode_badRequest() throws Exception {
        when(eventTypeService.getEventTypeByCode(anyString())).thenThrow(new IllegalArgumentException("bad code"));

        assertEnvelope(mockMvc.perform(get("/v1/eventTypes/code/{code}", "bad-code")), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("deleteEventType: unknown id is a 404 ApiError")
    void deleteEventType_notFound() throws Exception {
        when(eventTypeService.deleteEventType(any())).thenReturn(false);

        assertEnvelope(mockMvc.perform(delete("/v1/eventTypes/{id}", UUID.randomUUID())), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("queryEventsByEntity: rejected since is a 400 ApiError")
    void queryEventsByEntity_badRequest() throws Exception {
        when(eventQueryService.findByEntity(anyString(), any(), anyInt(), anyInt()))
                .thenThrow(new IllegalArgumentException("since must not be in the future"));

        assertEnvelope(mockMvc.perform(get("/v1/events").param("entityId", "ENTITY-1")), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("receiveEvent: an id that is not preregistered is a 400 ApiError, not a plain string")
    void receiveEvent_notPreregistered() throws Exception {
        when(emitEventService.receiveEvent(any())).thenReturn(false);

        assertEnvelope(
                mockMvc.perform(post("/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"ORDER_ORDER_CREATE","apiVersion":"1","timestamp":1730809200000,\
                                "elapsedMs":42,"publishedAt":"2026-03-05T21:15:00Z"}
                                """)),
                400,
                "VALIDATION_ERROR");
    }
}
