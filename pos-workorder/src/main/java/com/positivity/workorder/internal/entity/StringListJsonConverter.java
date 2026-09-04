package com.positivity.workorder.internal.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts List<String> values to and from JSON for TEXT columns.
 */
@Converter
public class StringListJsonConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        List<String> value = attribute == null ? List.of() : attribute;
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            // (issue #1694) JPA AttributeConverter failure on the module's own in-process
            // serialization of already-validated in-memory data -- a server-side defect, never a
            // client input problem. Left as a bare IllegalArgumentException so it now falls
            // through to the platform's generic 500 instead of the module's former blanket 400.
            throw new IllegalArgumentException("Unable to serialize string list", exception);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(dbData, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException exception) {
            // (issue #1694) Malformed persisted data -- a server-side defect (bad prior write or
            // manual DB edit), never a client input problem. Left as a bare
            // IllegalArgumentException so it now falls through to the platform's generic 500
            // instead of the module's former blanket 400.
            throw new IllegalArgumentException("Unable to deserialize string list", exception);
        }
    }
}
