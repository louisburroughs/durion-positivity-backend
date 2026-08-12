package com.positivity.poseventreceiver.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.poseventreceiver.internal.dto.EventTypeRequest;
import com.positivity.poseventreceiver.internal.dto.EventTypeResponse;
import com.positivity.poseventreceiver.service.EventTypeService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link EventTypeController}.
 *
 * <p>
 * The controller is a thin translation layer: it maps the service's
 * {@code Optional}/{@code boolean} results and its {@link IllegalArgumentException}
 * validation failures onto HTTP status codes. Those mappings are the contract
 * every registering service depends on — a create that collides on type code
 * must read as 400 rather than 500, and a missing id must read as 404 rather
 * than 200-with-null — so they are asserted directly here.
 *
 * <p>
 * Tested without a Spring context on purpose. Every method returns a
 * {@code ResponseEntity} it builds itself, so a mocked service reaches all of
 * them, including the error branches; a {@code @WebMvcTest} slice would add the
 * {@code @EmitEvent} aspect and the shared-secret filter without covering any
 * additional controller logic.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventTypeController — HTTP status mapping")
class EventTypeControllerTest {

    @Mock
    private EventTypeService eventTypeService;

    @InjectMocks
    private EventTypeController sut;

    private static EventTypeResponse response(String typeCode) {
        return new EventTypeResponse(
                UUID.randomUUID(), typeCode, "a description", true, "1", 200_000L, 1_000_000L, 3_000_000L);
    }

    private static EventTypeRequest request() {
        EventTypeRequest request = new EventTypeRequest();
        request.setTypeCode("ORDER_CREATE");
        request.setDescription("Create an order");
        return request;
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        @DisplayName("getAllEventTypes returns 200 with the full list")
        void getAll_returnsOk() {
            when(eventTypeService.getAllEventTypes()).thenReturn(List.of(response("A_ONE"), response("A_TWO")));

            ResponseEntity<List<EventTypeResponse>> result = sut.getAllEventTypes();

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).hasSize(2);
        }

        @Test
        @DisplayName("getAllEventTypes returns 200 with an empty list rather than 404")
        void getAll_whenEmpty_stillReturnsOk() {
            when(eventTypeService.getAllEventTypes()).thenReturn(List.of());

            ResponseEntity<List<EventTypeResponse>> result = sut.getAllEventTypes();

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isEmpty();
        }

        @Test
        @DisplayName("getActiveEventTypes returns 200 from the active-only query")
        void getActive_returnsOk() {
            when(eventTypeService.getActiveEventTypes()).thenReturn(List.of(response("A_ONE")));

            assertThat(sut.getActiveEventTypes().getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(eventTypeService, never()).getAllEventTypes();
        }

        @Test
        @DisplayName("getEventTypeById returns 200 when the id resolves")
        void getById_whenPresent_returnsOk() {
            UUID id = UUID.randomUUID();
            when(eventTypeService.getEventTypeById(id)).thenReturn(Optional.of(response("A_ONE")));

            ResponseEntity<EventTypeResponse> result = sut.getEventTypeById(id);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isNotNull();
        }

        @Test
        @DisplayName("getEventTypeById returns 404 when the id is unknown")
        void getById_whenAbsent_returnsNotFound() {
            UUID id = UUID.randomUUID();
            when(eventTypeService.getEventTypeById(id)).thenReturn(Optional.empty());

            assertThat(sut.getEventTypeById(id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("getEventTypeByCode returns 200 when the code resolves")
        void getByCode_whenPresent_returnsOk() {
            when(eventTypeService.getEventTypeByCode("ORDER_CREATE")).thenReturn(Optional.of(response("ORDER_CREATE")));

            ResponseEntity<EventTypeResponse> result = sut.getEventTypeByCode("ORDER_CREATE");

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isNotNull();
        }

        @Test
        @DisplayName("getEventTypeByCode returns 404 when the code is unknown")
        void getByCode_whenAbsent_returnsNotFound() {
            when(eventTypeService.getEventTypeByCode("NOPE")).thenReturn(Optional.empty());

            assertThat(sut.getEventTypeByCode("NOPE").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("getEventTypeByCode returns 400 when the code itself is malformed")
        void getByCode_whenCodeInvalid_returnsBadRequest() {
            when(eventTypeService.getEventTypeByCode(anyString()))
                    .thenThrow(new IllegalArgumentException("typeCode must contain only uppercase letters"));

            assertThat(sut.getEventTypeByCode("bad code!").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("createEventType")
    class Create {

        @Test
        @DisplayName("returns 201 with the created body")
        void create_whenAccepted_returnsCreated() {
            when(eventTypeService.createEventType(any())).thenReturn(Optional.of(response("ORDER_CREATE")));

            ResponseEntity<EventTypeResponse> result = sut.createEventType(request());

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().typeCode()).isEqualTo("ORDER_CREATE");
        }

        @Test
        @DisplayName("returns 400, not 409, when the type code already exists")
        void create_whenDuplicate_returnsBadRequest() {
            // The service signals a collision with an empty Optional; the controller
            // must not let that surface as a 200 with no body.
            when(eventTypeService.createEventType(any())).thenReturn(Optional.empty());

            ResponseEntity<EventTypeResponse> result = sut.createEventType(request());

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(result.getBody()).isNull();
        }

        @Test
        @DisplayName("returns 400 when the service rejects the request as invalid")
        void create_whenValidationFails_returnsBadRequest() {
            when(eventTypeService.createEventType(any()))
                    .thenThrow(new IllegalArgumentException("description is required"));

            assertThat(sut.createEventType(request()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("upsertEventType")
    class Upsert {

        @Test
        @DisplayName("returns 200 for both the create and update outcomes")
        void upsert_whenAccepted_returnsOk() {
            when(eventTypeService.upsertEventType(anyString(), any())).thenReturn(response("ORDER_CREATE"));

            ResponseEntity<EventTypeResponse> result = sut.upsertEventType("ORDER_CREATE", request());

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isNotNull();
        }

        @Test
        @DisplayName("returns 400 when the path code contradicts the request code")
        void upsert_whenCodesContradict_returnsBadRequest() {
            when(eventTypeService.upsertEventType(anyString(), any()))
                    .thenThrow(new IllegalArgumentException("Path typeCode must match request typeCode when provided"));

            assertThat(sut.upsertEventType("ORDER_CREATE", request()).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("updateEventType")
    class Update {

        @Test
        @DisplayName("returns 200 with the updated body")
        void update_whenPresent_returnsOk() {
            UUID id = UUID.randomUUID();
            when(eventTypeService.updateEventType(any(), any())).thenReturn(Optional.of(response("ORDER_CREATE")));

            ResponseEntity<EventTypeResponse> result = sut.updateEventType(id, request());

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isNotNull();
        }

        @Test
        @DisplayName("returns 404 when the id is unknown")
        void update_whenAbsent_returnsNotFound() {
            when(eventTypeService.updateEventType(any(), any())).thenReturn(Optional.empty());

            assertThat(sut.updateEventType(UUID.randomUUID(), request()).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("returns 400 when the service rejects the request as invalid")
        void update_whenValidationFails_returnsBadRequest() {
            when(eventTypeService.updateEventType(any(), any()))
                    .thenThrow(new IllegalArgumentException("p50Micros must be positive"));

            assertThat(sut.updateEventType(UUID.randomUUID(), request()).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("deleteEventType")
    class Delete {

        @Test
        @DisplayName("returns 204 with no body when the row was deleted")
        void delete_whenPresent_returnsNoContent() {
            UUID id = UUID.randomUUID();
            when(eventTypeService.deleteEventType(id)).thenReturn(true);

            ResponseEntity<Void> result = sut.deleteEventType(id);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(result.getBody()).isNull();
        }

        @Test
        @DisplayName("returns 404 when the id is unknown")
        void delete_whenAbsent_returnsNotFound() {
            when(eventTypeService.deleteEventType(any())).thenReturn(false);

            assertThat(sut.deleteEventType(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("returns 400 when the id is rejected as invalid")
        void delete_whenValidationFails_returnsBadRequest() {
            when(eventTypeService.deleteEventType(any())).thenThrow(new IllegalArgumentException("id is required"));

            assertThat(sut.deleteEventType(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * The controller logs caller-supplied type codes, so it masks them first. The
     * masking is a log-injection control, not cosmetics: a type code containing a
     * newline could otherwise forge a whole log line.
     */
    @Nested
    @DisplayName("log masking")
    class LogMasking {

        private String mask(Object value) {
            return (String) ReflectionTestUtils.invokeMethod(sut, "maskForLog", value);
        }

        @Test
        @DisplayName("renders null as the literal \"null\"")
        void mask_null() {
            assertThat(mask(null)).isEqualTo("null");
        }

        @ParameterizedTest(name = "\"{0}\" is fully masked (length <= 4)")
        @ValueSource(strings = {"a", "ab", "abc", "abcd"})
        @DisplayName("fully masks short values, which would otherwise be mostly readable")
        void mask_shortValues(String value) {
            assertThat(mask(value)).isEqualTo("****");
        }

        @ParameterizedTest(name = "\"{0}\" masks to \"{1}\"")
        @CsvSource({"ORDER_CREATE, OR***TE", "abcde, ab***de"})
        @DisplayName("keeps two leading and two trailing characters for longer values")
        void mask_longValues(String value, String expected) {
            assertThat(mask(value)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "no control character survives masking of {1}")
        @CsvSource({
            "'AA\rBBBB', a carriage return",
            "'AA\nBBBB', a line feed",
            "'AA\tBBBB', a tab",
        })
        @DisplayName("never emits CR, LF, or tab, so a crafted type code cannot forge a log line")
        void mask_neverEmitsControlCharacters(String value, String description) {
            assertThat(mask(value)).doesNotContain("\r", "\n", "\t");
        }

        @ParameterizedTest(name = "a control character at {1} is replaced with an underscore")
        @CsvSource({
            "'\rABCDE', the start,  _A***DE",
            "'ABCDE\r', the end,    AB***E_",
        })
        @DisplayName("replaces a control character that lands in the retained prefix or suffix")
        void mask_replacesControlCharacterInRetainedPositions(String value, String position, String expected) {
            // Masking keeps only the first two and last two characters, so a control
            // character in the middle is dropped by truncation regardless. The
            // replacement is what protects the positions that do survive.
            assertThat(mask(value)).isEqualTo(expected);
        }
    }
}
