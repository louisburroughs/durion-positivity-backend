package com.positivity.customer.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.events.EventTypeRegistration;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventTypesTest {

    @Test
    void all_includesBrowseEventAsDistinctFastReadRegistration() {
        List<EventTypeRegistration> registrations = EventTypes.all();
        assertThat(registrations)
                .extracting(EventTypeRegistration::getTypeCode)
                .doesNotHaveDuplicates()
                .contains("CUSTOMER_PARTY_BROWSE", "CUSTOMER_PARTY_SEARCH");

        EventTypeRegistration browseRegistration = registrations.stream()
                .filter(registration -> "CUSTOMER_PARTY_BROWSE".equals(registration.getTypeCode()))
                .findFirst()
                .orElseThrow();

        EventTypeRegistration searchRegistration = registrations.stream()
                .filter(registration -> "CUSTOMER_PARTY_SEARCH".equals(registration.getTypeCode()))
                .findFirst()
                .orElseThrow();

        assertThat(browseRegistration.getDescription()).isEqualTo("Browse parties with paging and sorting");
        assertThat(browseRegistration.getP50Micros()).isEqualTo(EventTypeRegistration.FAST_READ_P50);
        assertThat(browseRegistration.getP95Micros()).isEqualTo(EventTypeRegistration.FAST_READ_P95);
        assertThat(browseRegistration.getP99Micros()).isEqualTo(EventTypeRegistration.FAST_READ_P99);
        assertThat(browseRegistration).isNotEqualTo(searchRegistration);
    }
}
