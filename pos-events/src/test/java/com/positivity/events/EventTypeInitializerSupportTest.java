package com.positivity.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EventTypeInitializerSupport}, the shared loop every module's
 * {@code EventTypeInitializer} delegates to at startup.
 *
 * <p>
 * Its contract is deliberately fault-tolerant: a registration that throws is
 * logged and counted as a failure, and the loop continues. That is what keeps a
 * down {@code pos-event-receiver} from preventing ~20 services from starting, so
 * it is worth pinning down rather than inferring from each module's initializer.
 */
@DisplayName("EventTypeInitializerSupport — fault-tolerant registration loop")
class EventTypeInitializerSupportTest {

    private final EventTypeInitializerSupport sut = new EventTypeInitializerSupport("pos-test");

    private static EventTypeRegistration registration(String typeCode) {
        return EventTypeRegistration.write(typeCode, "description for " + typeCode)
                .build();
    }

    @Test
    @DisplayName("invokes the registration function once per event type, in iteration order")
    void registerEventTypes_invokesFunctionForEveryType() {
        List<EventTypeRegistration> types =
                List.of(registration("A_ONE"), registration("A_TWO"), registration("A_THREE"));
        List<String> seen = new ArrayList<>();

        int registered = sut.registerEventTypes(types, r -> seen.add(r.getTypeCode()));

        assertThat(seen).containsExactly("A_ONE", "A_TWO", "A_THREE");
        assertThat(registered).isEqualTo(3);
    }

    @Test
    @DisplayName("returns the success count, not the input size, when some registrations fail")
    void registerEventTypes_returnsSuccessCount() {
        List<EventTypeRegistration> types =
                List.of(registration("B_ONE"), registration("B_TWO"), registration("B_THREE"));

        int registered = sut.registerEventTypes(types, r -> {
            if (r.getTypeCode().equals("B_TWO")) {
                throw new IllegalStateException("receiver rejected " + r.getTypeCode());
            }
        });

        assertThat(registered).isEqualTo(2);
    }

    @Test
    @DisplayName("keeps going after a failure — a down receiver must not abort startup")
    void registerEventTypes_continuesAfterFailure() {
        List<EventTypeRegistration> types =
                List.of(registration("C_ONE"), registration("C_TWO"), registration("C_THREE"));
        List<String> attempted = new ArrayList<>();

        assertThatNoException()
                .isThrownBy(() -> sut.registerEventTypes(types, r -> {
                    attempted.add(r.getTypeCode());
                    throw new RuntimeException("connection refused");
                }));

        assertThat(attempted).containsExactly("C_ONE", "C_TWO", "C_THREE");
    }

    @Test
    @DisplayName("tolerates a failure whose exception carries no message")
    void registerEventTypes_toleratesNullExceptionMessage() {
        List<EventTypeRegistration> types = List.of(registration("D_ONE"));

        int registered = sut.registerEventTypes(types, r -> {
            throw new IllegalStateException();
        });

        assertThat(registered).isZero();
    }

    @Test
    @DisplayName("an empty collection registers nothing and reports zero")
    void registerEventTypes_withEmptyCollection_registersNothing() {
        List<String> seen = new ArrayList<>();

        int registered = sut.registerEventTypes(List.of(), r -> seen.add(r.getTypeCode()));

        assertThat(seen).isEmpty();
        assertThat(registered).isZero();
    }

    @Test
    @DisplayName("rejects a null event-type collection")
    void registerEventTypes_withNullTypes_throws() {
        assertThatNullPointerException().isThrownBy(() -> sut.registerEventTypes(null, r -> {}));
    }

    @Test
    @DisplayName("rejects a null registration function")
    void registerEventTypes_withNullFunction_throws() {
        List<EventTypeRegistration> types = List.of(registration("E_ONE"));

        assertThatNullPointerException().isThrownBy(() -> sut.registerEventTypes(types, null));
    }
}
