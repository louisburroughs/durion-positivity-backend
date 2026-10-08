package com.positivity.supplier.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.events.EmitEvent;
import com.positivity.events.EventTypeRegistration;
import com.positivity.supplier.internal.controller.SupplierVendorController;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every {@code @EmitEvent} on the vendor controller is registered with pos-event-receiver
 * ({@link SupplierEventTypes}), the reveal (#2621) included: an unregistered id is emitted with no budget.
 */
@DisplayName("SupplierEventTypes covers every vendor-controller @EmitEvent (#2621)")
class SupplierEventTypesVendorTest {

    @Test
    @DisplayName("each @EmitEvent id on SupplierVendorController is in SupplierEventTypes.all()")
    void everyVendorEmitEventIsRegistered() {
        Set<String> registered = SupplierEventTypes.all().stream()
                .map(EventTypeRegistration::getTypeCode)
                .collect(Collectors.toSet());
        List<String> emitted = Arrays.stream(SupplierVendorController.class.getDeclaredMethods())
                .map((Method method) -> method.getAnnotation(EmitEvent.class))
                .filter(annotation -> annotation != null)
                .map(EmitEvent::id)
                .toList();

        assertThat(emitted).contains("SUPPLIER_VENDOR_TAX_ID_REVEAL", "SUPPLIER_VENDOR_TAX_ID_REVEAL_LIST");
        assertThat(registered).containsAll(emitted);
    }
}
