package com.positivity.shopmanager.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.events.EmitEvent;
import com.positivity.events.EventTypeRegistration;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every {@code @EmitEvent} id this module annotates must be registered in {@link EventTypes}.
 *
 * <p>{@code EventTypeInitializer} preregisters only what {@code EventTypes.all()} lists, and
 * pos-event-receiver drops an emitted event whose id it was never told about. An annotation
 * without a registration therefore fails silently: the endpoint works, the audit trail the
 * annotation promises simply never appears, and nothing in the build says so.
 *
 * <p>That is not hypothetical — {@code SHOPMGR_SHOP_UPSERT} shipped annotated and unregistered,
 * and was caught in review rather than by a test. This closes the class rather than the instance.
 */
class EmitEventRegistrationCoverageTest {

    private static final JavaClasses MODULE_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("com.positivity.shopmanager");

    @Test
    @DisplayName("every @EmitEvent id is registered in EventTypes.all()")
    void everyAnnotatedEventIsRegistered() {
        Set<String> registered = EventTypes.all().stream()
                .map(EventTypeRegistration::getTypeCode)
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> annotated = MODULE_CLASSES.stream()
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .filter(method -> method.isAnnotatedWith(EmitEvent.class))
                .map(method -> method.getAnnotationOfType(EmitEvent.class).id())
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(annotated)
                .as("no @EmitEvent annotations found — the importer or the package is wrong, "
                        + "and a green result here would mean nothing")
                .isNotEmpty();

        assertThat(registered)
                .as("@EmitEvent ids missing from EventTypes.all(); pos-event-receiver silently "
                        + "drops these, so the audit event the annotation promises never lands")
                .containsAll(annotated);
    }

    @Test
    @DisplayName("no NEW dead registration — a registered id nothing emits must be a known one")
    void noNewDeadRegistrations() {
        // The other direction, so the registry does not accumulate ids for endpoints that were
        // renamed or removed. Eight are dead already: the SHOP_BAY_* and SHOP_MOBILE_UNIT_*
        // mutations moved to the location domain, and the two CREATED_FROM_* ids are unused
        // variants of SHOPMGR_APPOINTMENT_CREATED. Cleaning those up is not this change's job,
        // so they are listed rather than silently tolerated — the same shape as
        // scripts/rbac-audit-baseline.json. A ninth would fail here.
        Set<String> knownDead = Set.of(
                "SHOP_BAY_CREATE",
                "SHOP_BAY_MANAGE",
                "SHOP_BAY_DELETE",
                "SHOP_MOBILE_UNIT_CREATE",
                "SHOP_MOBILE_UNIT_MANAGE",
                "SHOP_MOBILE_UNIT_DELETE",
                "SHOPMGR_APPOINTMENT_CREATED_FROM_ESTIMATE",
                "SHOPMGR_APPOINTMENT_CREATED_FROM_WORKORDER");
        // Published by the domain-event path rather than a controller annotation.
        Set<String> emittedOutsideAnnotations = Set.of("SHOPMGR_APPOINTMENT_CREATED");

        Set<String> annotated = MODULE_CLASSES.stream()
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .filter(method -> method.isAnnotatedWith(EmitEvent.class))
                .map(method -> method.getAnnotationOfType(EmitEvent.class).id())
                .collect(Collectors.toCollection(TreeSet::new));

        List<String> dead = EventTypes.all().stream()
                .map(EventTypeRegistration::getTypeCode)
                .filter(code -> !annotated.contains(code))
                .filter(code -> !emittedOutsideAnnotations.contains(code))
                .filter(code -> !knownDead.contains(code))
                .toList();

        assertThat(dead)
                .as("newly dead registered event types; remove them, or emit them, rather than "
                        + "growing the known-dead list")
                .isEmpty();
    }
}
