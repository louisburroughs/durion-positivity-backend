package com.positivity.poseventreceiver.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.poseventreceiver.internal.entity.EventType;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EventTypeMapper}.
 *
 * <p>
 * {@code applyRequest} is a partial update: {@code description} and
 * {@code active} are always written, but {@code apiVersion} and the three
 * latency thresholds are only written when the request supplies them. That
 * asymmetry is the whole point of the mapper and the easiest thing to break —
 * dropping the null guards would silently reset every threshold to the entity
 * default whenever a caller omitted them, re-baselining the SLO of an event that
 * nobody intended to change.
 */
@DisplayName("EventTypeMapper — entity/DTO mapping")
class EventTypeMapperTest {

    private static EventTypeRequest fullRequest() {
        EventTypeRequest request = new EventTypeRequest();
        request.setTypeCode("ORDER_CREATE");
        request.setDescription("Create an order");
        request.setActive(true);
        request.setApiVersion("2");
        request.setP50Micros(11L);
        request.setP95Micros(22L);
        request.setP99Micros(33L);
        return request;
    }

    private static EventTypeRequest sparseRequest() {
        EventTypeRequest request = new EventTypeRequest();
        request.setDescription("Only a description");
        request.setActive(false);
        request.setApiVersion(null);
        request.setP50Micros(null);
        request.setP95Micros(null);
        request.setP99Micros(null);
        return request;
    }

    @Nested
    @DisplayName("toResponse")
    class ToResponse {

        @Test
        @DisplayName("copies every field onto the response")
        void toResponse_copiesAllFields() {
            EventType entity = new EventType("ORDER_CREATE", "Create an order", "2", 11L, 22L, 33L);
            UUID id = UUID.randomUUID();
            entity.setId(id);
            entity.setActive(true);

            EventTypeResponse response = EventTypeMapper.toResponse(entity);

            assertThat(response.id()).isEqualTo(id);
            assertThat(response.typeCode()).isEqualTo("ORDER_CREATE");
            assertThat(response.description()).isEqualTo("Create an order");
            assertThat(response.active()).isTrue();
            assertThat(response.apiVersion()).isEqualTo("2");
            assertThat(response.p50Micros()).isEqualTo(11L);
            assertThat(response.p95Micros()).isEqualTo(22L);
            assertThat(response.p99Micros()).isEqualTo(33L);
        }

        @Test
        @DisplayName("carries the inactive flag through rather than defaulting it")
        void toResponse_preservesInactiveFlag() {
            EventType entity = new EventType("ORDER_CREATE", "Create an order");
            entity.setActive(false);

            assertThat(EventTypeMapper.toResponse(entity).active()).isFalse();
        }
    }

    @Nested
    @DisplayName("toNewEntity")
    class ToNewEntity {

        @Test
        @DisplayName("uses the supplied type code, not the one on the request")
        void toNewEntity_usesSuppliedTypeCode() {
            EventTypeRequest request = fullRequest();
            request.setTypeCode("SOMETHING_ELSE");

            // The caller passes the normalized code separately; the request's own
            // typeCode must not win, or normalization would be bypassed here.
            EventType entity = EventTypeMapper.toNewEntity("ORDER_CREATE", request);

            assertThat(entity.getTypeCode()).isEqualTo("ORDER_CREATE");
        }

        @Test
        @DisplayName("applies every supplied field to the new entity")
        void toNewEntity_appliesSuppliedFields() {
            EventType entity = EventTypeMapper.toNewEntity("ORDER_CREATE", fullRequest());

            assertThat(entity.getDescription()).isEqualTo("Create an order");
            assertThat(entity.isActive()).isTrue();
            assertThat(entity.getApiVersion()).isEqualTo("2");
            assertThat(entity.getP50Micros()).isEqualTo(11L);
            assertThat(entity.getP95Micros()).isEqualTo(22L);
            assertThat(entity.getP99Micros()).isEqualTo(33L);
        }

        @Test
        @DisplayName("leaves entity defaults in place for fields the request omits")
        void toNewEntity_keepsDefaultsForOmittedFields() {
            EventType entity = EventTypeMapper.toNewEntity("ORDER_CREATE", sparseRequest());

            assertThat(entity.getApiVersion()).isEqualTo(EventType.DEFAULT_API_VERSION);
            assertThat(entity.getP50Micros()).isEqualTo(EventType.DEFAULT_THRESHOLD_MICROS);
            assertThat(entity.getP95Micros()).isEqualTo(EventType.DEFAULT_THRESHOLD_MICROS);
            assertThat(entity.getP99Micros()).isEqualTo(EventType.DEFAULT_THRESHOLD_MICROS);
        }
    }

    @Nested
    @DisplayName("applyRequest")
    class ApplyRequest {

        @Test
        @DisplayName("overwrites description and active unconditionally")
        void applyRequest_alwaysWritesDescriptionAndActive() {
            EventType existing = new EventType("ORDER_CREATE", "stale description");
            existing.setActive(true);

            EventTypeMapper.applyRequest(existing, sparseRequest());

            assertThat(existing.getDescription()).isEqualTo("Only a description");
            assertThat(existing.isActive()).isFalse();
        }

        @Test
        @DisplayName("leaves the existing apiVersion and thresholds untouched when the request omits them")
        void applyRequest_preservesExistingValuesForOmittedFields() {
            EventType existing = new EventType("ORDER_CREATE", "stale description", "7", 111L, 222L, 333L);

            EventTypeMapper.applyRequest(existing, sparseRequest());

            // A partial update must not silently reset an event's SLO.
            assertThat(existing.getApiVersion()).isEqualTo("7");
            assertThat(existing.getP50Micros()).isEqualTo(111L);
            assertThat(existing.getP95Micros()).isEqualTo(222L);
            assertThat(existing.getP99Micros()).isEqualTo(333L);
        }

        @Test
        @DisplayName("overwrites apiVersion and thresholds when the request supplies them")
        void applyRequest_writesSuppliedValues() {
            EventType existing = new EventType("ORDER_CREATE", "stale description", "7", 111L, 222L, 333L);

            EventTypeMapper.applyRequest(existing, fullRequest());

            assertThat(existing.getApiVersion()).isEqualTo("2");
            assertThat(existing.getP50Micros()).isEqualTo(11L);
            assertThat(existing.getP95Micros()).isEqualTo(22L);
            assertThat(existing.getP99Micros()).isEqualTo(33L);
        }

        @Test
        @DisplayName("never rewrites the type code — the natural key is immutable through an update")
        void applyRequest_neverRewritesTypeCode() {
            EventType existing = new EventType("ORDER_CREATE", "stale description");
            EventTypeRequest renaming = fullRequest();
            renaming.setTypeCode("SOMETHING_ELSE");

            EventTypeMapper.applyRequest(existing, renaming);

            assertThat(existing.getTypeCode()).isEqualTo("ORDER_CREATE");
        }

        @Test
        @DisplayName("applies thresholds independently — supplying one does not disturb the others")
        void applyRequest_appliesThresholdsIndependently() {
            EventType existing = new EventType("ORDER_CREATE", "stale description", "7", 111L, 222L, 333L);
            EventTypeRequest onlyP95 = sparseRequest();
            onlyP95.setP95Micros(999L);

            EventTypeMapper.applyRequest(existing, onlyP95);

            assertThat(existing.getP50Micros()).isEqualTo(111L);
            assertThat(existing.getP95Micros()).isEqualTo(999L);
            assertThat(existing.getP99Micros()).isEqualTo(333L);
        }
    }
}
