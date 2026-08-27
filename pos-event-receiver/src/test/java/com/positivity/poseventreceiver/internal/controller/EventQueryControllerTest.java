package com.positivity.poseventreceiver.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.positivity.poseventreceiver.internal.dto.EmittedEventResponse;
import com.positivity.poseventreceiver.internal.dto.PagedResponse;
import com.positivity.poseventreceiver.service.EventQueryService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for {@link EventQueryController}.
 *
 * <p>
 * Like {@link EventTypeControllerTest}, tested without a Spring context: the service's
 * {@link IllegalArgumentException} (out-of-bounds {@code since}) is the one failure mode
 * the controller itself translates, into a 400 with no body — mirroring every other GET in
 * this module that performs its own validation. Bean-validation bounds on page/size are
 * enforced by {@code @Validated} plus the platform's global exception handler and are not
 * exercised here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventQueryController — HTTP status mapping")
class EventQueryControllerTest {

    @Mock
    private EventQueryService eventQueryService;

    @InjectMocks
    private EventQueryController sut;

    private static PagedResponse<EmittedEventResponse> page(EmittedEventResponse... items) {
        return new PagedResponse<>(List.of(items), 0, 50, (long) items.length);
    }

    private static EmittedEventResponse response(String entityId) {
        return new EmittedEventResponse(
                UUID.randomUUID(), "ORDER_ORDER_CREATE", "1", 1_730_809_200_000L, 42L, Instant.EPOCH, entityId);
    }

    @Nested
    @DisplayName("queryEventsByEntity")
    class QueryEventsByEntity {

        @Test
        @DisplayName("returns 200 with the page the service produces")
        void returnsOkWithServiceResult() {
            when(eventQueryService.findByEntity(anyString(), any(), anyInt(), anyInt()))
                    .thenReturn(page(response("ENTITY-1")));

            ResponseEntity<PagedResponse<EmittedEventResponse>> result =
                    sut.queryEventsByEntity("ENTITY-1", null, 0, 50);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().getItems()).hasSize(1);
        }

        @Test
        @DisplayName("returns 200 with an empty page rather than 404 when nothing matches")
        void returnsOkWithEmptyPage() {
            when(eventQueryService.findByEntity(anyString(), any(), anyInt(), anyInt()))
                    .thenReturn(page());

            ResponseEntity<PagedResponse<EmittedEventResponse>> result =
                    sut.queryEventsByEntity("NO_SUCH_ENTITY", null, 0, 50);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().getItems()).isEmpty();
        }

        @Test
        @DisplayName("passes the caller-supplied since through to the service unchanged")
        void passesSinceThrough() {
            Instant since = Instant.parse("2026-08-20T00:00:00Z");
            when(eventQueryService.findByEntity(anyString(), any(), anyInt(), anyInt()))
                    .thenReturn(page());

            sut.queryEventsByEntity("ENTITY-1", since, 0, 50);

            org.mockito.Mockito.verify(eventQueryService).findByEntity("ENTITY-1", since, 0, 50);
        }

        @Test
        @DisplayName("returns 400 with no body when since is rejected as out of bounds")
        void returnsBadRequest_whenServiceRejectsSince() {
            when(eventQueryService.findByEntity(anyString(), any(), anyInt(), anyInt()))
                    .thenThrow(new IllegalArgumentException("since must not be more than 90 days in the past"));

            ResponseEntity<PagedResponse<EmittedEventResponse>> result =
                    sut.queryEventsByEntity("ENTITY-1", Instant.EPOCH, 0, 50);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(result.getBody()).isNull();
        }

        @Test
        @DisplayName("returns 400 with no body when since is rejected as being in the future")
        void returnsBadRequest_whenServiceRejectsFutureSince() {
            Instant future = Instant.now().plusSeconds(3600);
            when(eventQueryService.findByEntity(anyString(), any(), anyInt(), anyInt()))
                    .thenThrow(new IllegalArgumentException("since must not be in the future"));

            ResponseEntity<PagedResponse<EmittedEventResponse>> result =
                    sut.queryEventsByEntity("ENTITY-1", future, 0, 50);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(result.getBody()).isNull();
        }
    }
}
