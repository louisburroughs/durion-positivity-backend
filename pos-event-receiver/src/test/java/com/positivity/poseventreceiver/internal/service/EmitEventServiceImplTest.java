package com.positivity.poseventreceiver.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.poseventreceiver.dao.EventDao;
import com.positivity.poseventreceiver.internal.dto.EmitEventRequest;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link EmitEventServiceImpl}.
 *
 * <p>
 * This is the ingestion path: every {@code @EmitEvent}-annotated endpoint across
 * the platform ends up here. Two properties matter.
 *
 * <p>
 * <b>Preregistration is enforced, and rejection is not an exception.</b> An event
 * whose id was never registered is dropped and reported as {@code false} rather
 * than throwing, so an unregistered id degrades to lost telemetry instead of
 * failing the caller's business operation. That distinction is easy to erase by
 * "improving" the method to throw.
 *
 * <p>
 * <b>Validation runs before the preregistration lookup.</b> A malformed id must
 * be rejected outright rather than probed against the database.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmitEventServiceImpl — event ingestion and validation")
class EmitEventServiceImplTest {

    @Mock
    private EventDao eventDao;

    @InjectMocks
    private EmitEventServiceImpl sut;

    private static EmitEventRequest request() {
        return new EmitEventRequest(
                "ORDER_ORDER_CREATE", "1", 1_730_809_200_000L, 42L, Instant.parse("2026-03-05T21:15:00Z"));
    }

    private static EmitEventRequest requestWithId(String id) {
        return new EmitEventRequest(id, "1", 1L, 0L, Instant.EPOCH);
    }

    @Nested
    @DisplayName("receiveEvent")
    class ReceiveEvent {

        @Test
        @DisplayName("stores the event and reports true when the id is preregistered")
        void receiveEvent_whenPreregistered_storesAndReportsTrue() {
            when(eventDao.isPreregistered("ORDER_ORDER_CREATE")).thenReturn(true);

            assertThat(sut.receiveEvent(request())).isTrue();

            verify(eventDao).saveEmittedEvent(request());
        }

        @Test
        @DisplayName("drops the event and reports false when the id is not preregistered")
        void receiveEvent_whenNotPreregistered_dropsAndReportsFalse() {
            when(eventDao.isPreregistered("ORDER_ORDER_CREATE")).thenReturn(false);

            // Reported, not thrown: an unregistered id must degrade to lost
            // telemetry rather than failing the caller's business operation.
            assertThat(sut.receiveEvent(request())).isFalse();

            verify(eventDao, never()).saveEmittedEvent(any(EmitEventRequest.class));
        }

        @Test
        @DisplayName("accepts an elapsedMs of zero — an instantaneous operation is legitimate")
        void receiveEvent_withZeroElapsed_isAccepted() {
            when(eventDao.isPreregistered(anyString())).thenReturn(true);

            EmitEventRequest instantaneous = new EmitEventRequest("ORDER_ORDER_CREATE", "1", 1L, 0L, Instant.EPOCH);

            assertThat(sut.receiveEvent(instantaneous)).isTrue();
        }
    }

    @Nested
    @DisplayName("onEventEmitted")
    class OnEventEmitted {

        @Test
        @DisplayName("delegates to receiveEvent and stores a preregistered event")
        void onEventEmitted_storesPreregisteredEvent() {
            when(eventDao.isPreregistered("ORDER_ORDER_CREATE")).thenReturn(true);

            sut.onEventEmitted(request());

            verify(eventDao).saveEmittedEvent(request());
        }

        @Test
        @DisplayName("swallows the false outcome for an unregistered id — it returns void")
        void onEventEmitted_whenNotPreregistered_storesNothing() {
            when(eventDao.isPreregistered(anyString())).thenReturn(false);

            sut.onEventEmitted(request());

            verify(eventDao, never()).saveEmittedEvent(any(EmitEventRequest.class));
        }

        @Test
        @DisplayName("propagates a validation failure rather than silently dropping a malformed event")
        void onEventEmitted_whenInvalid_throws() {
            assertThatIllegalArgumentException().isThrownBy(() -> sut.onEventEmitted(requestWithId("lower_case")));
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("rejects a null request")
        void reject_nullRequest() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> sut.receiveEvent(null))
                    .withMessageContaining("cannot be null");
        }

        @ParameterizedTest(name = "id [{0}] is rejected as missing")
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("rejects a missing id")
        void reject_missingId(String id) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> sut.receiveEvent(requestWithId(id)))
                    .withMessageContaining("id is required");
        }

        @ParameterizedTest(name = "id \"{0}\" is rejected as malformed")
        @ValueSource(strings = {"lower_case", "ORDER-CREATE", "ORDER CREATE", "ORDER.CREATE", "ORDÉR"})
        @DisplayName("rejects an id that is not uppercase letters, digits, and underscores")
        void reject_malformedId(String id) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> sut.receiveEvent(requestWithId(id)))
                    .withMessageContaining("uppercase letters");
        }

        @ParameterizedTest(name = "id \"{0}\" is accepted")
        @ValueSource(strings = {"ORDER_CREATE", "ORDER2", "A", "A_1_B"})
        @DisplayName("accepts a well-formed id")
        void accept_wellFormedId(String id) {
            when(eventDao.isPreregistered(id)).thenReturn(true);

            assertThat(sut.receiveEvent(requestWithId(id))).isTrue();
        }

        @Test
        @DisplayName("validates before touching the database — a malformed id is never looked up")
        void validation_runsBeforePreregistrationLookup() {
            assertThatIllegalArgumentException().isThrownBy(() -> sut.receiveEvent(requestWithId("bad id")));

            verify(eventDao, never()).isPreregistered(anyString());
        }

        @ParameterizedTest(name = "apiVersion [{0}] is rejected")
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("rejects a missing apiVersion")
        void reject_missingApiVersion(String apiVersion) {
            EmitEventRequest invalid = new EmitEventRequest("ORDER_CREATE", apiVersion, 1L, 0L, Instant.EPOCH);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> sut.receiveEvent(invalid))
                    .withMessageContaining("apiVersion is required");
        }

        @ParameterizedTest(name = "apiVersion \"{0}\" is rejected as non-numeric")
        @ValueSource(strings = {"v1", "1.0", "one", "-1"})
        @DisplayName("rejects a non-numeric apiVersion")
        void reject_nonNumericApiVersion(String apiVersion) {
            EmitEventRequest invalid = new EmitEventRequest("ORDER_CREATE", apiVersion, 1L, 0L, Instant.EPOCH);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> sut.receiveEvent(invalid))
                    .withMessageContaining("apiVersion must be numeric");
        }

        @ParameterizedTest(name = "timestamp {0} is rejected: {1}")
        @CsvSource({"0, zero is not a valid epoch millisecond", "-1, negative epoch millisecond"})
        @DisplayName("rejects a non-positive timestamp")
        void reject_nonPositiveTimestamp(long timestamp, String why) {
            EmitEventRequest invalid = new EmitEventRequest("ORDER_CREATE", "1", timestamp, 0L, Instant.EPOCH);

            assertThatIllegalArgumentException()
                    .as(why)
                    .isThrownBy(() -> sut.receiveEvent(invalid))
                    .withMessageContaining("timestamp must be positive");
        }

        @Test
        @DisplayName("rejects a negative elapsedMs")
        void reject_negativeElapsed() {
            EmitEventRequest invalid = new EmitEventRequest("ORDER_CREATE", "1", 1L, -1L, Instant.EPOCH);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> sut.receiveEvent(invalid))
                    .withMessageContaining("elapsedMs must be zero or positive");
        }

        @Test
        @DisplayName("rejects a missing publishedAt")
        void reject_missingPublishedAt() {
            EmitEventRequest invalid = new EmitEventRequest("ORDER_CREATE", "1", 1L, 0L, null);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> sut.receiveEvent(invalid))
                    .withMessageContaining("publishedAt is required");
        }
    }
}
