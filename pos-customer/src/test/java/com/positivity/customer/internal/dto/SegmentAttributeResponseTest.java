package com.positivity.customer.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.customer.internal.domain.SegmentAttribute;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** #1144: the attribute catalog is documented as available via the catalog endpoint. */
class SegmentAttributeResponseTest {

    @Test
    @DisplayName("every catalog entry maps with a unique wire name and a non-blank description")
    void everyAttributeIsDocumented() {
        var responses = Arrays.stream(SegmentAttribute.values())
                .map(SegmentAttributeResponse::from)
                .toList();

        assertThat(responses).hasSameSizeAs(SegmentAttribute.values());
        assertThat(responses.stream().map(SegmentAttributeResponse::attribute)).doesNotHaveDuplicates();
        assertThat(responses).allSatisfy(response -> {
            assertThat(response.description()).isNotBlank();
            assertThat(response.operators()).isNotEmpty();
        });
    }

    @Test
    @DisplayName("service-due appears as a boolean attribute available to both audiences")
    void serviceDueIsInTheCatalog() {
        SegmentAttributeResponse serviceDue = SegmentAttributeResponse.from(SegmentAttribute.SERVICE_DUE);

        assertThat(serviceDue.attribute()).isEqualTo("service.due");
        assertThat(serviceDue.operandKind()).isEqualTo("BOOLEAN");
        assertThat(serviceDue.operators()).containsExactlyInAnyOrder("IS_TRUE", "IS_FALSE");
        assertThat(serviceDue.commercialOnly()).isFalse();
        assertThat(serviceDue.keyed()).isFalse();
    }

    @Test
    @DisplayName("only the keyed external-identifier attribute is flagged as keyed")
    void flagsKeyedAttributes() {
        assertThat(SegmentAttributeResponse.from(SegmentAttribute.EXTERNAL_IDENTIFIER)
                        .keyed())
                .isTrue();
        assertThat(Arrays.stream(SegmentAttribute.values())
                        .map(SegmentAttributeResponse::from)
                        .filter(SegmentAttributeResponse::keyed))
                .hasSize(1);
    }
}
