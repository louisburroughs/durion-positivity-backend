package com.positivity.poseventreceiver.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.poseventreceiver.internal.dto.EmittedEventResponse;
import com.positivity.poseventreceiver.internal.dto.PagedResponse;
import com.positivity.poseventreceiver.internal.entity.EmittedEvent;
import com.positivity.poseventreceiver.internal.repository.EmittedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Unit tests for {@link EventQueryServiceImpl}.
 *
 * <p>
 * Covers the lookback defaulting/bounding rules from issue #1521 (default 7 days, rejected
 * beyond 90 days in the past or any time in the future), the fixed publishedAt-descending
 * sort, and the entity -> DTO mapping.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventQueryServiceImpl — entity-indexed event query")
class EventQueryServiceImplTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-08-27T12:00:00Z");

    @Mock
    private EmittedEventRepository emittedEventRepository;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private EventQueryServiceImpl service;

    private static EmittedEvent event(String entityId, Instant publishedAt) {
        EmittedEvent event = new EmittedEvent("ORDER_ORDER_CREATE", "1", 1L, 5L, publishedAt, entityId);
        event.setEventId(UUID.randomUUID());
        return event;
    }

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new EventQueryServiceImpl(emittedEventRepository, fixedClock);
    }

    @Nested
    @DisplayName("since defaulting and bounds")
    class SinceBounds {

        @Test
        @DisplayName("defaults since to 7 days ago when not supplied")
        void defaultsToSevenDaysAgo() {
            when(emittedEventRepository.findByEntityIdAndPublishedAtGreaterThanEqual(any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            service.findByEntity("ENTITY-1", null, 0, 50);

            verify(emittedEventRepository)
                    .findByEntityIdAndPublishedAtGreaterThanEqual(
                            eq("ENTITY-1"), eq(Instant.parse("2026-08-20T12:00:00Z")), any());
        }

        @Test
        @DisplayName("rejects a since more than 90 days in the past")
        void rejectsSinceOlderThan90Days() {
            Instant tooOld = FIXED_NOW.minus(java.time.Duration.ofDays(91));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.findByEntity("ENTITY-1", tooOld, 0, 50))
                    .withMessageContaining("90 days");
        }

        @Test
        @DisplayName("accepts a since exactly 90 days in the past")
        void acceptsSinceExactly90DaysAgo() {
            Instant exactly90 = FIXED_NOW.minus(java.time.Duration.ofDays(90));
            when(emittedEventRepository.findByEntityIdAndPublishedAtGreaterThanEqual(any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            service.findByEntity("ENTITY-1", exactly90, 0, 50);

            verify(emittedEventRepository)
                    .findByEntityIdAndPublishedAtGreaterThanEqual(eq("ENTITY-1"), eq(exactly90), any());
        }

        @Test
        @DisplayName("rejects a since in the future")
        void rejectsFutureSince() {
            Instant future = FIXED_NOW.plusSeconds(60);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.findByEntity("ENTITY-1", future, 0, 50))
                    .withMessageContaining("future");
        }

        @Test
        @DisplayName("accepts since equal to now")
        void acceptsSinceEqualToNow() {
            when(emittedEventRepository.findByEntityIdAndPublishedAtGreaterThanEqual(any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            service.findByEntity("ENTITY-1", FIXED_NOW, 0, 50);

            verify(emittedEventRepository)
                    .findByEntityIdAndPublishedAtGreaterThanEqual(eq("ENTITY-1"), eq(FIXED_NOW), any());
        }
    }

    @Nested
    @DisplayName("paging and sort")
    class PagingAndSort {

        @Test
        @DisplayName("delegates with a Pageable sorted publishedAt DESC, honoring page and size")
        void delegatesWithFixedDescendingSort() {
            when(emittedEventRepository.findByEntityIdAndPublishedAtGreaterThanEqual(any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 10), 0));

            service.findByEntity("ENTITY-1", null, 2, 10);

            verify(emittedEventRepository)
                    .findByEntityIdAndPublishedAtGreaterThanEqual(eq("ENTITY-1"), any(), pageableCaptor.capture());
            Pageable captured = pageableCaptor.getValue();
            assertThat(captured.getPageNumber()).isEqualTo(2);
            assertThat(captured.getPageSize()).isEqualTo(10);
            assertThat(captured.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "publishedAt"));
        }
    }

    @Nested
    @DisplayName("response mapping")
    class ResponseMapping {

        @Test
        @DisplayName("maps entities to EmittedEventResponse and wraps the page metadata")
        void mapsEntitiesAndPageMetadata() {
            Instant publishedAt = FIXED_NOW.minusSeconds(120);
            EmittedEvent stored = event("ENTITY-1", publishedAt);
            when(emittedEventRepository.findByEntityIdAndPublishedAtGreaterThanEqual(any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(stored), PageRequest.of(0, 50), 1));

            PagedResponse<EmittedEventResponse> result = service.findByEntity("ENTITY-1", null, 0, 50);

            assertThat(result.getItems()).hasSize(1);
            EmittedEventResponse response = result.getItems().getFirst();
            assertThat(response.eventId()).isEqualTo(stored.getEventId());
            assertThat(response.id()).isEqualTo("ORDER_ORDER_CREATE");
            assertThat(response.apiVersion()).isEqualTo("1");
            assertThat(response.elapsedMs()).isEqualTo(5L);
            assertThat(response.publishedAt()).isEqualTo(publishedAt);
            assertThat(response.entityId()).isEqualTo("ENTITY-1");
            assertThat(result.getPageNumber()).isZero();
            assertThat(result.getPageSize()).isEqualTo(50);
            assertThat(result.getTotalCount()).isEqualTo(1L);
        }

        @Test
        @DisplayName("returns an empty page rather than throwing when nothing matches")
        void emptyResultIsAnEmptyPage() {
            when(emittedEventRepository.findByEntityIdAndPublishedAtGreaterThanEqual(any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

            PagedResponse<EmittedEventResponse> result = service.findByEntity("NO_SUCH_ENTITY", null, 0, 50);

            assertThat(result.getItems()).isEmpty();
            assertThat(result.getTotalCount()).isZero();
        }
    }
}
