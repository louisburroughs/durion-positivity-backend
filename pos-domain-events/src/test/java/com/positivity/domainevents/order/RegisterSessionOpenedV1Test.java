package com.positivity.domainevents.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("RegisterSessionOpenedV1 (order.session.opened v1; #2571, #2573)")
class RegisterSessionOpenedV1Test {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private static final UUID SESSION = UUID.fromString("019a0000-0000-7000-8000-0000000000c1");
    private static final Instant OPENED = Instant.parse("2026-10-07T08:00:00Z");

    @Test
    @DisplayName("round-trips, with and without a location")
    void roundTrips() {
        RegisterSessionOpenedV1 opened = new RegisterSessionOpenedV1(
                SESSION, "T-1", UUID.fromString("019a0000-0000-7000-8000-00000000a001"), OPENED);
        RegisterSessionOpenedV1 noLocation = new RegisterSessionOpenedV1(SESSION, "T-1", null, OPENED);

        assertThat(MAPPER.readValue(MAPPER.writeValueAsString(opened), RegisterSessionOpenedV1.class))
                .isEqualTo(opened);
        assertThat(MAPPER.readValue(MAPPER.writeValueAsString(noLocation), RegisterSessionOpenedV1.class))
                .isEqualTo(noLocation);
        assertThat(RegisterSessionOpenedV1.EVENT_TYPE).isEqualTo("order.session.opened");
        assertThat(RegisterSessionOpenedV1.SCHEMA_VERSION).isEqualTo(1);
    }

    @Test
    @DisplayName("refuses a missing session, a blank terminal or a missing opening time")
    void validates() {
        assertThatThrownBy(() -> new RegisterSessionOpenedV1(null, "T-1", null, OPENED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegisterSessionOpenedV1(SESSION, " ", null, OPENED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegisterSessionOpenedV1(SESSION, null, null, OPENED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegisterSessionOpenedV1(SESSION, "T-1", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
