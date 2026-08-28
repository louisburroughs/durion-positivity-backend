package com.positivity.poseventreceiver.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.poseventreceiver.internal.dao.EventDao;
import com.positivity.poseventreceiver.internal.dto.EventTypeRequest;
import com.positivity.poseventreceiver.internal.dto.EventTypeResponse;
import com.positivity.poseventreceiver.internal.entity.EventType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link EventTypeServiceImpl}.
 *
 * <p>
 * This is the write path behind {@code pos-event-receiver}'s event-type API,
 * which every other service calls at startup to register the events it emits.
 * Two things carry the weight here:
 *
 * <ul>
 * <li><b>Type-code normalization.</b> Codes are trimmed and upper-cased before
 * any lookup or write. Because the code is the natural key used for upsert, a
 * normalization gap would let {@code order_create} and {@code ORDER_CREATE}
 * become two rows, and a registering service would then silently fail to update
 * the row it thought it owned.</li>
 * <li><b>Threshold validation.</b> The p50/p95/p99 values become the latency
 * SLO for the event, so the service rejects non-positive and out-of-order
 * triples rather than storing an SLO that can never be met or never be
 * breached.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventTypeServiceImpl — event-type lifecycle and validation")
class EventTypeServiceImplTest {

    @Mock
    private EventDao eventDao;

    @InjectMocks
    private EventTypeServiceImpl sut;

    @Captor
    private ArgumentCaptor<EventType> savedEventType;

    private static EventTypeRequest request() {
        EventTypeRequest request = new EventTypeRequest();
        request.setTypeCode("ORDER_CREATE");
        request.setDescription("Create an order");
        request.setActive(true);
        request.setApiVersion("1");
        request.setP50Micros(200_000L);
        request.setP95Micros(1_000_000L);
        request.setP99Micros(3_000_000L);
        return request;
    }

    private static EventType entity(String typeCode) {
        EventType eventType = new EventType(typeCode, "existing description");
        eventType.setId(UUID.randomUUID());
        return eventType;
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        @DisplayName("getAllEventTypes maps every row to a response")
        void getAllEventTypes_mapsAllRows() {
            when(eventDao.getAllEventTypes()).thenReturn(List.of(entity("ORDER_CREATE"), entity("ORDER_CANCEL")));

            assertThat(sut.getAllEventTypes())
                    .extracting(EventTypeResponse::typeCode)
                    .containsExactly("ORDER_CREATE", "ORDER_CANCEL");
        }

        @Test
        @DisplayName("getActiveEventTypes delegates to the active-only query")
        void getActiveEventTypes_usesActiveQuery() {
            when(eventDao.getActiveEventTypes()).thenReturn(List.of(entity("ORDER_CREATE")));

            assertThat(sut.getActiveEventTypes()).hasSize(1);
            verify(eventDao, never()).getAllEventTypes();
        }

        @Test
        @DisplayName("getEventTypeById returns empty when the id is unknown")
        void getEventTypeById_whenAbsent_returnsEmpty() {
            UUID id = UUID.randomUUID();
            when(eventDao.getEventType(id)).thenReturn(Optional.empty());

            assertThat(sut.getEventTypeById(id)).isEmpty();
        }

        @Test
        @DisplayName("getEventTypeById maps the row when present")
        void getEventTypeById_whenPresent_mapsRow() {
            EventType existing = entity("ORDER_CREATE");
            when(eventDao.getEventType(existing.getId())).thenReturn(Optional.of(existing));

            assertThat(sut.getEventTypeById(existing.getId()))
                    .get()
                    .extracting(EventTypeResponse::typeCode)
                    .isEqualTo("ORDER_CREATE");
        }

        @ParameterizedTest(name = "getEventTypeByCode normalizes \"{0}\" to ORDER_CREATE")
        @ValueSource(strings = {"ORDER_CREATE", "order_create", "  ORDER_CREATE  ", "Order_Create"})
        @DisplayName("getEventTypeByCode trims and upper-cases before lookup")
        void getEventTypeByCode_normalizesBeforeLookup(String supplied) {
            when(eventDao.getEventTypeByCode("ORDER_CREATE")).thenReturn(Optional.of(entity("ORDER_CREATE")));

            assertThat(sut.getEventTypeByCode(supplied)).isPresent();
            verify(eventDao).getEventTypeByCode("ORDER_CREATE");
        }
    }

    @Nested
    @DisplayName("createEventType")
    class Create {

        @Test
        @DisplayName("persists a new row and returns its response")
        void create_whenCodeUnused_persists() {
            when(eventDao.getEventTypeByCode("ORDER_CREATE")).thenReturn(Optional.empty());
            when(eventDao.saveEventType(any(EventType.class))).thenAnswer(i -> i.getArgument(0));

            assertThat(sut.createEventType(request()))
                    .get()
                    .extracting(EventTypeResponse::typeCode)
                    .isEqualTo("ORDER_CREATE");

            verify(eventDao).saveEventType(savedEventType.capture());
            assertThat(savedEventType.getValue().getDescription()).isEqualTo("Create an order");
            assertThat(savedEventType.getValue().getP50Micros()).isEqualTo(200_000L);
        }

        @Test
        @DisplayName("returns empty and writes nothing when the type code already exists")
        void create_whenCodeTaken_returnsEmptyWithoutWriting() {
            when(eventDao.getEventTypeByCode("ORDER_CREATE")).thenReturn(Optional.of(entity("ORDER_CREATE")));

            assertThat(sut.createEventType(request())).isEmpty();
            verify(eventDao, never()).saveEventType(any());
        }

        @Test
        @DisplayName("normalizes the request type code before the uniqueness check")
        void create_normalizesCodeBeforeUniquenessCheck() {
            EventTypeRequest lowercase = request();
            lowercase.setTypeCode("order_create");
            when(eventDao.getEventTypeByCode("ORDER_CREATE")).thenReturn(Optional.of(entity("ORDER_CREATE")));

            // Without normalization this would look up "order_create", miss, and
            // create a duplicate row for an event that is already registered.
            assertThat(sut.createEventType(lowercase)).isEmpty();
        }
    }

    @Nested
    @DisplayName("upsertEventType")
    class Upsert {

        @Test
        @DisplayName("updates the existing row in place when the code is already registered")
        void upsert_whenPresent_updatesInPlace() {
            EventType existing = entity("ORDER_CREATE");
            when(eventDao.getEventTypeByCode("ORDER_CREATE")).thenReturn(Optional.of(existing));
            when(eventDao.saveEventType(any(EventType.class))).thenAnswer(i -> i.getArgument(0));

            EventTypeResponse response = sut.upsertEventType("ORDER_CREATE", request());

            assertThat(response.description()).isEqualTo("Create an order");
            verify(eventDao).saveEventType(savedEventType.capture());
            assertThat(savedEventType.getValue()).isSameAs(existing);
        }

        @Test
        @DisplayName("creates the row when the code is not yet registered")
        void upsert_whenAbsent_creates() {
            when(eventDao.getEventTypeByCode("ORDER_CREATE")).thenReturn(Optional.empty());
            when(eventDao.saveEventType(any(EventType.class))).thenAnswer(i -> i.getArgument(0));

            assertThat(sut.upsertEventType("ORDER_CREATE", request()).typeCode())
                    .isEqualTo("ORDER_CREATE");
        }

        @Test
        @DisplayName("accepts a request whose type code matches the path once both are normalized")
        void upsert_whenRequestCodeMatchesAfterNormalization_succeeds() {
            EventTypeRequest lowercase = request();
            lowercase.setTypeCode("order_create");
            when(eventDao.getEventTypeByCode("ORDER_CREATE")).thenReturn(Optional.empty());
            when(eventDao.saveEventType(any(EventType.class))).thenAnswer(i -> i.getArgument(0));

            assertThat(sut.upsertEventType("ORDER_CREATE", lowercase).typeCode())
                    .isEqualTo("ORDER_CREATE");
        }

        @Test
        @DisplayName("rejects a request whose type code contradicts the path")
        void upsert_whenRequestCodeContradictsPath_throws() {
            EventTypeRequest mismatched = request();
            mismatched.setTypeCode("ORDER_CANCEL");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> sut.upsertEventType("ORDER_CREATE", mismatched))
                    .withMessageContaining("must match");
            verify(eventDao, never()).saveEventType(any());
        }

        @ParameterizedTest(name = "omitted request type code ({0}) falls back to the path code")
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("treats an absent request type code as agreement with the path")
        void upsert_whenRequestCodeAbsent_usesPathCode(String supplied) {
            EventTypeRequest withoutCode = request();
            withoutCode.setTypeCode(supplied);
            when(eventDao.getEventTypeByCode("ORDER_CREATE")).thenReturn(Optional.empty());
            when(eventDao.saveEventType(any(EventType.class))).thenAnswer(i -> i.getArgument(0));

            assertThat(sut.upsertEventType("ORDER_CREATE", withoutCode).typeCode())
                    .isEqualTo("ORDER_CREATE");
        }
    }

    @Nested
    @DisplayName("updateEventType and deleteEventType")
    class UpdateAndDelete {

        @Test
        @DisplayName("updateEventType returns empty and writes nothing when the id is unknown")
        void update_whenAbsent_returnsEmpty() {
            UUID id = UUID.randomUUID();
            when(eventDao.getEventType(id)).thenReturn(Optional.empty());

            assertThat(sut.updateEventType(id, request())).isEmpty();
            verify(eventDao, never()).saveEventType(any());
        }

        @Test
        @DisplayName("updateEventType applies the request to the existing row")
        void update_whenPresent_appliesRequest() {
            EventType existing = entity("ORDER_CREATE");
            when(eventDao.getEventType(existing.getId())).thenReturn(Optional.of(existing));
            when(eventDao.saveEventType(any(EventType.class))).thenAnswer(i -> i.getArgument(0));

            assertThat(sut.updateEventType(existing.getId(), request()))
                    .get()
                    .extracting(EventTypeResponse::description)
                    .isEqualTo("Create an order");
            assertThat(existing.getP99Micros()).isEqualTo(3_000_000L);
        }

        @Test
        @DisplayName("deleteEventType reports false and deletes nothing when the id is unknown")
        void delete_whenAbsent_returnsFalse() {
            UUID id = UUID.randomUUID();
            when(eventDao.eventTypeExists(id)).thenReturn(false);

            assertThat(sut.deleteEventType(id)).isFalse();
            verify(eventDao, never()).deleteEventType(any());
        }

        @Test
        @DisplayName("deleteEventType deletes and reports true when the id exists")
        void delete_whenPresent_deletes() {
            UUID id = UUID.randomUUID();
            when(eventDao.eventTypeExists(id)).thenReturn(true);

            assertThat(sut.deleteEventType(id)).isTrue();
            verify(eventDao).deleteEventType(id);
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @ParameterizedTest(name = "type code \"{0}\" is rejected")
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "order-create", "ORDER CREATE", "ORDER.CREATE", "ORDÉR"})
        @DisplayName("rejects a missing or non-conforming type code on create")
        void create_withInvalidTypeCode_throws(String typeCode) {
            EventTypeRequest invalid = request();
            invalid.setTypeCode(typeCode);

            assertThatIllegalArgumentException().isThrownBy(() -> sut.createEventType(invalid));
            verify(eventDao, never()).saveEventType(any());
        }

        @ParameterizedTest(name = "description \"{0}\" is rejected")
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("rejects a missing description")
        void create_withBlankDescription_throws(String description) {
            EventTypeRequest invalid = request();
            invalid.setDescription(description);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> sut.createEventType(invalid))
                    .withMessageContaining("description");
        }

        @ParameterizedTest(name = "apiVersion \"{0}\" is rejected")
        @ValueSource(strings = {"", "   ", "v1", "1.0", "one"})
        @DisplayName("rejects a non-numeric apiVersion")
        void create_withNonNumericApiVersion_throws(String apiVersion) {
            EventTypeRequest invalid = request();
            invalid.setApiVersion(apiVersion);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> sut.createEventType(invalid))
                    .withMessageContaining("apiVersion");
        }

        @Test
        @DisplayName("accepts a null apiVersion — the entity default applies")
        void create_withNullApiVersion_isAccepted() {
            EventTypeRequest withoutVersion = request();
            withoutVersion.setApiVersion(null);
            when(eventDao.getEventTypeByCode(anyString())).thenReturn(Optional.empty());
            when(eventDao.saveEventType(any(EventType.class))).thenAnswer(i -> i.getArgument(0));

            assertThat(sut.createEventType(withoutVersion)).isPresent();
        }

        @ParameterizedTest(name = "thresholds {0}/{1}/{2} are rejected: {3}")
        @CsvSource({
            "0,      1000000, 3000000, p50 must be positive",
            "-1,     1000000, 3000000, negative p50",
            "200000, 0,       3000000, p95 must be positive",
            "200000, 1000000, 0,       p99 must be positive",
            "2000000, 1000000, 3000000, p50 above p95",
            "200000, 4000000, 3000000, p95 above p99",
            "4000000, 4000000, 3000000, p50 above p99",
        })
        @DisplayName("rejects non-positive or out-of-order latency thresholds")
        void create_withInvalidThresholds_throws(long p50, long p95, long p99, String why) {
            EventTypeRequest invalid = request();
            invalid.setP50Micros(p50);
            invalid.setP95Micros(p95);
            invalid.setP99Micros(p99);

            assertThatIllegalArgumentException().as(why).isThrownBy(() -> sut.createEventType(invalid));
            verify(eventDao, never()).saveEventType(any());
        }

        @Test
        @DisplayName("accepts equal thresholds — the bound is inclusive")
        void create_withEqualThresholds_isAccepted() {
            EventTypeRequest flat = request();
            flat.setP50Micros(1_000L);
            flat.setP95Micros(1_000L);
            flat.setP99Micros(1_000L);
            when(eventDao.getEventTypeByCode(anyString())).thenReturn(Optional.empty());
            when(eventDao.saveEventType(any(EventType.class))).thenAnswer(i -> i.getArgument(0));

            assertThat(sut.createEventType(flat)).isPresent();
        }

        @Test
        @DisplayName("accepts null thresholds — the entity defaults apply")
        void create_withNullThresholds_isAccepted() {
            EventTypeRequest withoutThresholds = request();
            withoutThresholds.setP50Micros(null);
            withoutThresholds.setP95Micros(null);
            withoutThresholds.setP99Micros(null);
            when(eventDao.getEventTypeByCode(anyString())).thenReturn(Optional.empty());
            when(eventDao.saveEventType(any(EventType.class))).thenAnswer(i -> i.getArgument(0));

            assertThat(sut.createEventType(withoutThresholds)).isPresent();
        }

        @Test
        @DisplayName("rejects a null request")
        void create_withNullRequest_throws() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> sut.createEventType(null))
                    .withMessageContaining("request is required");
        }

        @Test
        @DisplayName("rejects a null id")
        void getById_withNullId_throws() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> sut.getEventTypeById(null))
                    .withMessageContaining("id is required");
        }
    }
}
