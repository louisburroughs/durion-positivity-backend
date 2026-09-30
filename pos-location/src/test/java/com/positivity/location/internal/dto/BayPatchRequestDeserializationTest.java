package com.positivity.location.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Deserialization of {@link BayPatchRequest} through a default Jackson 3 mapper (issue #2251).
 *
 * <p>{@code maxDutyClass} null means "no limit", so an absent key and an explicit JSON null must
 * read differently: the first leaves the bay alone, the second clears its ceiling.
 */
@DisplayName("BayPatchRequest deserialization")
class BayPatchRequestDeserializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("an explicit maxDutyClass null is present with a null value")
    void explicitNullIsPresent() {
        BayPatchRequest patch = objectMapper.readValue("{\"maxDutyClass\":null}", BayPatchRequest.class);

        assertThat(patch.isMaxDutyClassPresent()).isTrue();
        assertThat(patch.getMaxDutyClass()).isNull();
    }

    @Test
    @DisplayName("an absent maxDutyClass key is not present")
    void absentKeyIsNotPresent() {
        BayPatchRequest patch = objectMapper.readValue("{}", BayPatchRequest.class);

        assertThat(patch.isMaxDutyClassPresent()).isFalse();
        assertThat(patch.getMaxDutyClass()).isNull();
    }

    @Test
    @DisplayName("a maxDutyClass number is present with its value")
    void numberIsPresent() {
        BayPatchRequest patch =
                objectMapper.readValue("{\"name\":\"Bay A1\",\"maxDutyClass\":4}", BayPatchRequest.class);

        assertThat(patch.isMaxDutyClassPresent()).isTrue();
        assertThat(patch.getMaxDutyClass()).isEqualTo(4);
        assertThat(patch.getName()).isEqualTo("Bay A1");
    }

    @Test
    @DisplayName("the present flag is not a wire property")
    void flagIsNotSerialized() {
        String json = objectMapper.writeValueAsString(
                BayPatchRequest.builder().maxDutyClass(3).build());

        assertThat(json).contains("\"maxDutyClass\":3").doesNotContain("maxDutyClassPresent");
    }

    @Test
    @DisplayName("the builder marks maxDutyClass present, null included")
    void builderMarksPresent() {
        assertThat(BayPatchRequest.builder().maxDutyClass(null).build().isMaxDutyClassPresent())
                .isTrue();
        assertThat(BayPatchRequest.builder().name("x").build().isMaxDutyClassPresent())
                .isFalse();
    }
}
