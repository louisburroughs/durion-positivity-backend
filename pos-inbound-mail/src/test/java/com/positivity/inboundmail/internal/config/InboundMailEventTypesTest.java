package com.positivity.inboundmail.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.events.EmitEvent;
import com.positivity.events.EventTypeRegistration;
import com.positivity.inboundmail.internal.controller.AddressController;
import com.positivity.inboundmail.internal.controller.StagedAttachmentController;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("InboundMailEventTypes: every @EmitEvent id is registered")
class InboundMailEventTypesTest {

    @Test
    void registersTheThreeEndpointEvents() {
        assertThat(InboundMailEventTypes.all())
                .extracting(EventTypeRegistration::getTypeCode)
                .containsExactlyInAnyOrder(
                        "INBOUND_MAIL_ADDRESS_ISSUE",
                        "INBOUND_MAIL_ADDRESS_ROTATE",
                        "INBOUND_MAIL_STAGED_ATTACHMENT_GET");
    }

    @Test
    void everyEmitEventOnTheControllersIsRegistered() {
        List<String> emitted = new ArrayList<>();
        for (Class<?> controller : List.of(AddressController.class, StagedAttachmentController.class)) {
            for (Method method : controller.getDeclaredMethods()) {
                EmitEvent emit = method.getAnnotation(EmitEvent.class);
                if (emit != null) {
                    emitted.add(emit.id());
                }
            }
        }
        assertThat(emitted)
                .containsExactlyInAnyOrderElementsOf(InboundMailEventTypes.all().stream()
                        .map(EventTypeRegistration::getTypeCode)
                        .toList());
    }
}
