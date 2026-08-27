package com.positivity.location.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.positivity.location.internal.enums.AllowNewProductPolicy;
import com.positivity.location.internal.enums.StorageCategory;
import com.positivity.location.internal.enums.StorageLocationType;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Deserialization of {@link StorageLocationRequest} through a default Jackson 3 mapper.
 *
 * <p>This exists because a primitive {@code boolean} on this DTO made every payload that omitted it
 * unreadable, and the only test that caught it was a Failsafe IT — which runs on {@code main} but not
 * on pull requests, so the break reached {@code main} behind a green PR. These are plain unit tests so
 * the contract is checked in the {@code test} phase, where a PR can see it.
 *
 * <p>The mechanism worth remembering: Jackson 3 enables {@code FAIL_ON_NULL_FOR_PRIMITIVES} by
 * default (Jackson 2 did not) and treats Lombok's {@code @AllArgsConstructor} as a property-based
 * creator, so an omitted optional field is passed to the constructor as null. A primitive parameter
 * then fails the entire request with 400 rather than defaulting. Every optional field on a request
 * DTO must therefore be a boxed type.
 */
@DisplayName("StorageLocationRequest deserialization")
class StorageLocationRequestDeserializationTest {

    /** A default mapper, matching the strictness the application context applies. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("#1514 - the exact payload the contract tests post is readable")
    void contractTestPayloadIsReadable() {
        // Copied verbatim from StorageLocationContractBehaviorIT's create tests, which is what
        // actually broke. barcode is optional and present here for that reason rather than because
        // it is required — the point is the payload that turned main red, character for character.
        String json = "{\"name\":\"Floor-New\",\"barcode\":\"BAR-DUP\",\"type\":\"FLOOR\"}";

        assertThatCode(() -> objectMapper.readValue(json, StorageLocationRequest.class))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("#1514 - omitting hazardContainment leaves it unstated rather than failing the request")
    void omittedHazardContainmentIsUnstated() {
        StorageLocationRequest request =
                objectMapper.readValue("{\"name\":\"N\",\"type\":\"FLOOR\"}", StorageLocationRequest.class);

        // Null, not false: the service coerces it when building the entity. What matters here is
        // that an omitted optional field does not make the payload unreadable.
        assertThat(request.getHazardContainment()).isNull();
    }

    @Test
    @DisplayName("an explicit hazardContainment is honoured either way")
    void explicitHazardContainmentIsRead() {
        assertThat(objectMapper
                        .readValue(
                                "{\"name\":\"N\",\"type\":\"FLOOR\",\"hazardContainment\":true}",
                                StorageLocationRequest.class)
                        .getHazardContainment())
                .isTrue();
        assertThat(objectMapper
                        .readValue(
                                "{\"name\":\"N\",\"type\":\"FLOOR\",\"hazardContainment\":false}",
                                StorageLocationRequest.class)
                        .getHazardContainment())
                .isFalse();
    }

    @Test
    @DisplayName("the capability fields round-trip when supplied")
    void capabilityFieldsRoundTrip() {
        String json = "{\"name\":\"Main Battery Rack\",\"type\":\"SHELF\","
                + "\"storageCategoryCode\":\"BATTERY_RACK\",\"hazardContainment\":true,"
                + "\"allowNewProduct\":\"SAME_PRODUCT_ONLY\"}";

        StorageLocationRequest request = objectMapper.readValue(json, StorageLocationRequest.class);

        assertThat(request.getType()).isEqualTo(StorageLocationType.SHELF);
        assertThat(request.getStorageCategoryCode()).isEqualTo(StorageCategory.BATTERY_RACK);
        assertThat(request.getAllowNewProduct()).isEqualTo(AllowNewProductPolicy.SAME_PRODUCT_ONLY);
        assertThat(request.getHazardContainment()).isTrue();
    }

    @Test
    @DisplayName("no field on this DTO is a primitive, since Jackson 3 cannot default one")
    void noFieldIsAPrimitive() {
        // Guards the class of bug rather than one field: a future optional primitive would turn every
        // payload omitting it into a 400, and this fails the moment one is added.
        assertThat(Arrays.stream(StorageLocationRequest.class.getDeclaredFields())
                        .filter(field -> !field.isSynthetic())
                        .filter(field -> field.getType().isPrimitive())
                        .map(Field::getName)
                        .toList())
                .as("request DTO fields that are primitives")
                .isEmpty();
    }
}
