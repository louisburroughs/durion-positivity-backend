package com.positivity.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("ApiError factories (ADR-0017 §3)")
class ApiErrorTest {

    @Test
    @DisplayName("of() and the nine-field constructor leave the itemized-conflict fields absent")
    void envelopeWithoutConflicts() {
        ApiError plain = ApiError.of("NOT_FOUND", "missing", 404, "2026-09-19T12:00:00Z", "corr-1");
        ApiError nineField =
                new ApiError("NOT_FOUND", "missing", 404, "2026-09-19T12:00:00Z", "corr-1", null, null, null, null);

        assertThat(plain.conflicts()).isNull();
        assertThat(plain.suggestedAlternatives()).isNull();
        assertThat(nineField).isEqualTo(plain);
    }

    @Test
    @DisplayName("withConflicts() carries the conflicts and alternatives, and no other optional field")
    void envelopeWithConflicts() {
        ApiError.Conflict hard = new ApiError.Conflict("HARD", "BAY_DOUBLE_BOOKED", "Bay 1 is booked", false, "bay-1");
        ApiError.SuggestedAlternative slot = new ApiError.SuggestedAlternative(
                "2026-06-18T09:00:00-05:00", "2026-06-18T10:00:00-05:00", "Bay 2 free");

        ApiError error = ApiError.withConflicts(
                "SCHEDULING_CONFLICT",
                "HARD conflicts cannot be overridden",
                409,
                "2026-09-19T12:00:00Z",
                "corr-2",
                List.of(hard),
                List.of(slot));

        assertThat(error.code()).isEqualTo("SCHEDULING_CONFLICT");
        assertThat(error.status()).isEqualTo(409);
        assertThat(error.conflicts()).containsExactly(hard);
        assertThat(error.suggestedAlternatives()).containsExactly(slot);
        assertThat(error.fieldErrors()).isNull();
        assertThat(error.referenceId()).isNull();
    }

    @Test
    @DisplayName("optional fields are omitted from the JSON, including inside conflicts entries")
    void omitsAbsentOptionalFields() throws Exception {
        ApiError error = ApiError.withConflicts(
                "SCHEDULING_CONFLICT",
                "HARD conflicts cannot be overridden",
                409,
                "2026-09-19T12:00:00Z",
                "corr-3",
                List.of(new ApiError.Conflict("HARD", "BAY_DOUBLE_BOOKED", "Bay 1 is booked", false, null)),
                List.of(new ApiError.SuggestedAlternative(
                        "2026-06-18T09:00:00-05:00", "2026-06-18T10:00:00-05:00", null)));

        String json = JsonMapper.builder().build().writeValueAsString(error);

        assertThat(json)
                .doesNotContain("affectedResource")
                .doesNotContain("reason")
                .doesNotContain("fieldErrors")
                .doesNotContain("null")
                .contains("\"code\":\"SCHEDULING_CONFLICT\"")
                .contains("\"severity\":\"HARD\"")
                .contains("\"startDateTime\"");
    }
}
