package com.positivity.workorder.internal.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link StringListJsonConverter} — JPA AttributeConverter for List&lt;String&gt; ↔ JSON TEXT columns.
 *
 * <p>Verifies all branches of the converter: null/blank/empty inputs, valid JSON arrays,
 * and invalid JSON error handling. Created as part of Story #93 (CAP-094) coverage hardening.</p>
 */
@DisplayName("StringListJsonConverter Unit Tests")
class StringListJsonConverterTest {

    private final StringListJsonConverter converter = new StringListJsonConverter();

    // =====================================================================
    // convertToDatabaseColumn
    // =====================================================================

    @Test
    @DisplayName("convertToDatabaseColumn: null attribute → returns '[]'")
    void convertToDatabaseColumn_null_returnsEmptyJsonArray() {
        String result = converter.convertToDatabaseColumn(null);
        assertThat(result).isEqualTo("[]");
    }

    @Test
    @DisplayName("convertToDatabaseColumn: empty list → returns '[]'")
    void convertToDatabaseColumn_emptyList_returnsEmptyJsonArray() {
        String result = converter.convertToDatabaseColumn(new ArrayList<>());
        assertThat(result).isEqualTo("[]");
    }

    @Test
    @DisplayName("convertToDatabaseColumn: single-element list → returns JSON array with one element")
    void convertToDatabaseColumn_singleElement_returnsJsonArray() {
        String result = converter.convertToDatabaseColumn(List.of("abc-123"));
        assertThat(result).isEqualTo("[\"abc-123\"]");
    }

    @Test
    @DisplayName("convertToDatabaseColumn: multi-element list → returns JSON array preserving order")
    void convertToDatabaseColumn_multipleElements_returnsOrderedJsonArray() {
        String result = converter.convertToDatabaseColumn(List.of("a", "b", "c"));
        assertThat(result).isEqualTo("[\"a\",\"b\",\"c\"]");
    }

    @Test
    @DisplayName("convertToDatabaseColumn: list with UUID-like strings → serialises correctly")
    void convertToDatabaseColumn_uuidStrings_serialisesCorrectly() {
        List<String> contactIds = List.of(
                "01952f4e-a003-7000-8000-crm0000003",
                "01952f4e-a004-7000-8000-crm0000004"
        );
        String result = converter.convertToDatabaseColumn(contactIds);
        assertThat(result)
                .contains("01952f4e-a003-7000-8000-crm0000003")
                .contains("01952f4e-a004-7000-8000-crm0000004")
                .startsWith("[")
                .endsWith("]");
    }

    // =====================================================================
    // convertToEntityAttribute
    // =====================================================================

    @Test
    @DisplayName("convertToEntityAttribute: null dbData → returns empty ArrayList")
    void convertToEntityAttribute_null_returnsEmptyList() {
        List<String> result = converter.convertToEntityAttribute(null);
        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("convertToEntityAttribute: empty string → returns empty ArrayList")
    void convertToEntityAttribute_emptyString_returnsEmptyList() {
        List<String> result = converter.convertToEntityAttribute("");
        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("convertToEntityAttribute: blank/whitespace string → returns empty ArrayList")
    void convertToEntityAttribute_blankString_returnsEmptyList() {
        List<String> result = converter.convertToEntityAttribute("   ");
        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("convertToEntityAttribute: '[]' → returns empty ArrayList")
    void convertToEntityAttribute_emptyJsonArray_returnsEmptyList() {
        List<String> result = converter.convertToEntityAttribute("[]");
        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("convertToEntityAttribute: single-element JSON array → returns list with one element")
    void convertToEntityAttribute_singleElementJson_returnsListWithOneElement() {
        List<String> result = converter.convertToEntityAttribute("[\"abc-123\"]");
        assertThat(result).containsExactly("abc-123");
    }

    @Test
    @DisplayName("convertToEntityAttribute: multi-element JSON array → returns ordered list")
    void convertToEntityAttribute_multipleElementJson_returnsOrderedList() {
        List<String> result = converter.convertToEntityAttribute("[\"x\",\"y\",\"z\"]");
        assertThat(result).containsExactly("x", "y", "z");
    }

    @Test
    @DisplayName("convertToEntityAttribute: UUID-like strings round-trip correctly")
    void convertToEntityAttribute_uuidStrings_roundTripsCorrectly() {
        String crmContactId1 = "01952f4e-a003-7000-8000-crm0000003";
        String crmContactId2 = "01952f4e-a004-7000-8000-crm0000004";
        String json = "[\"" + crmContactId1 + "\",\"" + crmContactId2 + "\"]";

        List<String> result = converter.convertToEntityAttribute(json);

        assertThat(result).containsExactly(crmContactId1, crmContactId2);
    }

    @Test
    @DisplayName("convertToEntityAttribute: invalid JSON → throws IllegalArgumentException")
    void convertToEntityAttribute_invalidJson_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("not-valid-json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unable to deserialize string list");
    }

    // =====================================================================
    // Round-trip symmetry
    // =====================================================================

    @Test
    @DisplayName("Round-trip: convertToDatabaseColumn then convertToEntityAttribute returns equal list")
    void roundTrip_withContactIds_returnsSameList() {
        List<String> original = List.of("id-001", "id-002", "id-003");

        String serialised = converter.convertToDatabaseColumn(original);
        List<String> deserialised = converter.convertToEntityAttribute(serialised);

        assertThat(deserialised).isEqualTo(original);
    }

    @Test
    @DisplayName("Round-trip: null → '[]' → empty list (null-safe persistence)")
    void roundTrip_null_persistsAndDeserializesAsEmptyList() {
        String serialised = converter.convertToDatabaseColumn(null);
        List<String> deserialised = converter.convertToEntityAttribute(serialised);

        assertThat(serialised).isEqualTo("[]");
        assertThat(deserialised).isEmpty();
    }
}
