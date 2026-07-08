package com.positivity.domainevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class DomainTopicsTest {

    @Test
    void buildsVersionedFactCommandAndDlqTopicNames() {
        assertThat(DomainTopics.events("customer")).isEqualTo("customer.events.v1");
        assertThat(DomainTopics.events("people-contact")).isEqualTo("people-contact.events.v1");
        assertThat(DomainTopics.commands("invoice")).isEqualTo("invoice.commands.v1");
        assertThat(DomainTopics.dlq("invoice.commands.v1")).isEqualTo("invoice.commands.v1.dlq");
        assertThat(DomainTopics.events("workorder")).isEqualTo(DomainTopics.WORKORDER_EVENTS_V1);
    }

    @Test
    void rejectsInvalidDomainNames() {
        assertThatIllegalArgumentException().isThrownBy(() -> DomainTopics.events("Customer"));
        assertThatIllegalArgumentException().isThrownBy(() -> DomainTopics.commands("pos_customer"));
        assertThatIllegalArgumentException().isThrownBy(() -> DomainTopics.dlq(" "));
        assertThatIllegalArgumentException().isThrownBy(() -> DomainTopics.dlq(null));
    }
}
