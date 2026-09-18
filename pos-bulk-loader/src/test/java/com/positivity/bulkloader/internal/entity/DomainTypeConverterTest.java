package com.positivity.bulkloader.internal.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.bulkloader.internal.enums.DomainType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("DomainTypeConverter (#2070)")
class DomainTypeConverterTest {

    private final DomainTypeConverter converter = new DomainTypeConverter();

    @ParameterizedTest
    @EnumSource(DomainType.class)
    void roundTripsEveryConstantByName(DomainType domainType) {
        String stored = converter.convertToDatabaseColumn(domainType);

        assertThat(stored).isEqualTo(domainType.name());
        assertThat(converter.convertToEntityAttribute(stored)).isEqualTo(domainType);
    }

    @Test
    void readsANameTheEnumNoLongerHasAsRetired() {
        assertThat(converter.convertToEntityAttribute("MECHANIC_SKILL")).isEqualTo(DomainType.RETIRED);
    }

    @Test
    void passesNullThrough() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
