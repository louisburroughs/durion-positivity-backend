package com.positivity.people.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.people.internal.enums.ContactPointType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Locks the JSON wire name of the contact-point primary flag to "primary" in BOTH
 * directions. The getter serialized as "primary" while the field deserialized from
 * "isPrimary" — an asymmetry that 400'd PUT /v1/people/{id}/contact-points and broke
 * the pos-customer dual-write.
 */
class PersonContactPointDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesPrimaryFlagAsPrimary() {
        String json =
                mapper.writeValueAsString(new Person.ContactPointDto(ContactPointType.EMAIL, "g@example.com", true));

        assertThat(json).contains("\"primary\":true");
        assertThat(json).doesNotContain("isPrimary");
    }

    @Test
    void deserializesFromPrimaryField() {
        Person.ContactPointDto dto = mapper.readValue(
                "{\"contactType\":\"EMAIL\",\"value\":\"g@example.com\",\"primary\":true}",
                Person.ContactPointDto.class);

        assertThat(dto.getContactType()).isEqualTo(ContactPointType.EMAIL);
        assertThat(dto.getValue()).isEqualTo("g@example.com");
        assertThat(dto.isPrimary()).isTrue();
    }

    @Test
    void deserializesPrimaryFalse() {
        Person.ContactPointDto dto = mapper.readValue(
                "{\"contactType\":\"PHONE_WORK\",\"value\":\"704-555-1\",\"primary\":false}",
                Person.ContactPointDto.class);

        assertThat(dto.isPrimary()).isFalse();
    }
}
