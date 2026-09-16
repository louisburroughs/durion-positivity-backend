package com.positivity.vehicle.internal.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Pins the D13 class table, including the class 3 / class 4 line the record calls Durion's own. */
class DutyCategoryTest {

    @ParameterizedTest(name = "class {0} is {1}")
    @CsvSource({
        "1, LIGHT", "2, LIGHT", "3, LIGHT",
        "4, MEDIUM", "5, MEDIUM", "6, MEDIUM",
        "7, HEAVY", "8, HEAVY"
    })
    void everyClassMapsPerTheRecord(int gvwrClass, DutyCategory expected) {
        assertThat(DutyCategory.fromGvwrClass(gvwrClass)).isEqualTo(expected);
    }

    @Test
    @DisplayName("class 3 is light-duty here — the ASE A/T line, not the alternative FHWA grouping")
    void classThreeIsLight() {
        assertThat(DutyCategory.fromGvwrClass(3)).isEqualTo(DutyCategory.LIGHT);
        assertThat(DutyCategory.fromGvwrClass(4)).isEqualTo(DutyCategory.MEDIUM);
    }

    @ParameterizedTest(name = "class {0} is rejected")
    @CsvSource({"0", "9", "-1"})
    void outsideTheTableIsAnError(int gvwrClass) {
        assertThatThrownBy(() -> DutyCategory.fromGvwrClass(gvwrClass))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1..8");
    }
}
