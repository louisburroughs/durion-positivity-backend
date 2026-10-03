package com.positivity.platformsender.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.events.EventTypeRegistration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PlatformSenderEventTypes — every @EmitEvent id is registered")
class PlatformSenderEventTypesTest {

    @Test
    void registersTheSendEvent() {
        assertThat(PlatformSenderEventTypes.all())
                .extracting(EventTypeRegistration::getTypeCode)
                .containsExactly("PLATFORM_SENDER_MESSAGE_SEND");
    }
}
