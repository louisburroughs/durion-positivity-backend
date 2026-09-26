package com.positivity.location.internal.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** DECISION-LOCATION-025: {@code acceptsGeneralWork} is false only for {@code WASH_DETAIL}. */
class BayTypeTest {

    @Test
    @DisplayName("WASH_DETAIL is the sole type that does not accept general work")
    void onlyWashDetailIsExcluded() {
        for (BayType bayType : BayType.values()) {
            if (bayType == BayType.WASH_DETAIL) {
                assertThat(bayType.acceptsGeneralWork()).as(bayType.name()).isFalse();
            } else {
                assertThat(bayType.acceptsGeneralWork()).as(bayType.name()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("A specialty bay type (ALIGNMENT) still accepts general work, ranked last per D14")
    void specialtyTypeStillAcceptsGeneralWork() {
        assertThat(BayType.ALIGNMENT.acceptsGeneralWork()).isTrue();
    }

    @Test
    @DisplayName("HEAVY_DUTY is a duty-class ceiling (D13), not a specialty exclusion")
    void heavyDutyAcceptsGeneralWork() {
        assertThat(BayType.HEAVY_DUTY.acceptsGeneralWork()).isTrue();
    }
}
