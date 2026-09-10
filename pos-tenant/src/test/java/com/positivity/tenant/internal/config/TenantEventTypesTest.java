package com.positivity.tenant.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.events.EmitEvent;
import com.positivity.events.EventTypeRegistration;
import com.positivity.tenant.internal.controller.PlatformAccountController;
import com.positivity.tenant.internal.controller.PlatformTenantController;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Every {@code @EmitEvent} id on a controller is registered, and every registration is used. */
class TenantEventTypesTest {

    @Test
    void registryMatchesTheControllersExactly() {
        Set<String> emitted = Stream.of(PlatformTenantController.class, PlatformAccountController.class)
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .map(method -> method.getAnnotation(EmitEvent.class))
                .filter(java.util.Objects::nonNull)
                .map(EmitEvent::id)
                .collect(Collectors.toSet());
        Set<String> registered = TenantEventTypes.all().stream()
                .map(EventTypeRegistration::getTypeCode)
                .collect(Collectors.toSet());

        assertThat(registered).containsExactlyInAnyOrderElementsOf(emitted);
        assertThat(TenantEventTypes.all()).hasSize(15);
    }

    @Test
    void everyMutationIsAnnotated() {
        for (Method method : PlatformTenantController.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                assertThat(method.getAnnotation(EmitEvent.class))
                        .as("%s emits an event", method.getName())
                        .isNotNull();
            }
        }
    }
}
