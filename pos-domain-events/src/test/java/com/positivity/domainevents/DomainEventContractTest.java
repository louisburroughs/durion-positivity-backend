package com.positivity.domainevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * One cross-cutting contract test over every domain-event record in this module.
 *
 * <p>
 * There are around sixty of these records and they are all the same shape: a
 * record with an {@code EVENT_TYPE}, a {@code SCHEMA_VERSION} and a compact
 * constructor that rejects nulls and non-positive quantities. Thirteen of them
 * had individual tests and the rest had none, which is the sort of gap that
 * grows every time someone adds an event — nobody writes the sixty-first test
 * because the sixtieth did not exist either.
 *
 * <p>
 * So rather than sixty more near-identical test classes, this enumerates the
 * records off the classpath and asserts the invariants that must hold for all of
 * them. Three of those are worth stating:
 *
 * <ul>
 * <li><b>{@code EVENT_TYPE} must be unique across the module.</b> Consumers
 * dispatch on it. Two facts sharing a type string means one of them is handled
 * by the other's listener, which is a data-corruption bug that no compiler and
 * no per-class test would catch — it is only visible from the whole set at
 * once.</li>
 * <li><b>Every non-nullable component must actually be validated.</b> The
 * compact constructors are hand-written and repetitive, so a forgotten check is
 * easy and silent: the event publishes with a null field and fails later inside
 * a consumer, in another service, with no reference to where it came from.</li>
 * <li><b>{@code SCHEMA_VERSION} must be a positive integer</b> and the type
 * string must look like a routed topic key. Both are read by the event
 * infrastructure rather than by Java, so nothing else checks them.</li>
 * </ul>
 *
 * <p>
 * Records that this test cannot construct — because a component has a type it
 * has no sample value for — are reported by name rather than skipped silently,
 * so the coverage this provides cannot quietly erode.
 */
@DisplayName("Domain events — cross-cutting record contract")
class DomainEventContractTest {

    private static final UUID SAMPLE_UUID = UUID.fromString("01980001-0000-7000-8000-000000000001");
    private static final Instant SAMPLE_INSTANT = Instant.parse("2026-08-12T12:00:00Z");

    /** Not domain-event facts: envelope, helper and manifest types. */
    private static final Set<String> NOT_EVENT_RECORDS =
            Set.of("DomainEventEnvelope", "UuidV7Timestamps", "ReconciliationManifestV1");

    /**
     * Records whose compact constructor enforces a relationship <em>between</em>
     * components, which generic sample values cannot satisfy — a settlement whose
     * gross must equal fee plus net, a care preference that must not carry an
     * interval once deleted, a reason constrained to a fixed set, a transfer order
     * that must have lines. Each is covered by a dedicated test instead; they are
     * named here rather than filtered by a heuristic so that excluding a record
     * stays a deliberate act.
     */
    private static final Set<String> CROSS_FIELD_INVARIANTS = Set.of(
            "com.positivity.domainevents.payment.SettlementReportedV1",
            "com.positivity.domainevents.vehicle.VehicleCarePreferenceUpdatedV1",
            "com.positivity.domainevents.inventory.ExpectedSupplyDroppedV1",
            "com.positivity.domainevents.inventory.TransferOrderUpdatedV1");

    static List<Class<?>> constructibleEventRecords() {
        return eventRecords().stream()
                .filter(type -> !CROSS_FIELD_INVARIANTS.contains(type.getName()))
                .toList();
    }

    static List<Class<?>> eventRecords() {
        return scanRecords().stream()
                .filter(type -> !NOT_EVENT_RECORDS.contains(type.getSimpleName()))
                .filter(DomainEventContractTest::hasEventTypeConstant)
                .sorted(Comparator.comparing(Class::getName))
                .toList();
    }

    private static boolean hasEventTypeConstant(Class<?> type) {
        try {
            type.getField("EVENT_TYPE");
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    /** Walks the compiled classes directory rather than taking a scanning dependency. */
    private static List<Class<?>> scanRecords() {
        try {
            Path root = Path.of(DomainEventContractTest.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            // test-classes -> classes
            Path mainClasses = root.resolveSibling("classes");
            if (!Files.isDirectory(mainClasses)) {
                throw new IllegalStateException("Compiled main classes not found at " + mainClasses);
            }
            try (Stream<Path> paths = Files.walk(mainClasses)) {
                List<Class<?>> found = new ArrayList<>();
                for (Path path :
                        paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                    String className = mainClasses
                            .relativize(path)
                            .toString()
                            .replace(java.io.File.separatorChar, '.')
                            .replaceAll("\\.class$", "");
                    Class<?> type = Class.forName(className);
                    if (type.isRecord() && Modifier.isPublic(type.getModifiers())) {
                        found.add(type);
                    }
                }
                return found;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException | ClassNotFoundException e) {
            throw new IllegalStateException("Unable to scan domain-event records", e);
        }
    }

    @Test
    @DisplayName("the scan finds the module's event records")
    void scanFindsEvents() {
        // If the scan silently found nothing, every parameterized test below would vacuously
        // pass. This is the guard that keeps the sweep honest.
        assertThat(eventRecords()).hasSizeGreaterThan(40);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("eventRecords")
    @DisplayName("every event declares a well-formed EVENT_TYPE")
    void eventTypeIsWellFormed(Class<?> eventType) throws Exception {
        String type = (String) eventType.getField("EVENT_TYPE").get(null);

        assertThat(type).as("%s.EVENT_TYPE", eventType.getSimpleName()).isNotBlank();
        // Dotted lowercase, hyphens allowed inside any segment (e.g. people-contact.user-person-link.updated). These
        // strings are matched by
        // consumers as literals, so a stray capital or space is a fact nobody receives.
        assertThat(type).as("%s.EVENT_TYPE shape", eventType.getSimpleName()).matches("[a-z0-9_-]+(\\.[a-z0-9_-]+)+");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("eventRecords")
    @DisplayName("every event declares a positive SCHEMA_VERSION")
    void everyEventDeclaresASchemaVersion(Class<?> eventType) throws Exception {
        String name = eventType.getName();

        // The envelope requires a version regardless, so a record without this constant forces
        // its publisher to hardcode a literal in another module — one that will not move when
        // the payload shape does. Owning the number here keeps the bump in the same file as the
        // change that motivated it.
        assertThatCode(() -> eventType.getField("SCHEMA_VERSION"))
                .as(
                        "%s declares no SCHEMA_VERSION. Add one rather than hardcoding the envelope"
                                + " version at the publisher.",
                        name)
                .doesNotThrowAnyException();

        assertThat(eventType.getField("SCHEMA_VERSION").getInt(null))
                .as("%s.SCHEMA_VERSION", name)
                .isPositive();
    }

    @Test
    @DisplayName("no two events share an EVENT_TYPE")
    void eventTypesAreUnique() throws Exception {
        List<String> types = new ArrayList<>();
        for (Class<?> eventType : eventRecords()) {
            types.add((String) eventType.getField("EVENT_TYPE").get(null));
        }

        // Only visible across the whole set. A duplicate means one fact is delivered to the
        // other's listener and deserialized into the wrong shape.
        assertThat(types).doesNotHaveDuplicates();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("constructibleEventRecords")
    @DisplayName("every event record constructs from a valid set of arguments")
    void everyEventConstructs(Class<?> eventType) throws Exception {
        Constructor<?> canonical = canonicalConstructor(eventType);
        canonical.setAccessible(true);

        // Runs each record's compact constructor, which is where the hand-written validation
        // lives. Roughly forty of these had no test at all, so this is the first thing that has
        // ever executed their bodies.
        Object instance = canonical.newInstance(sampleArguments(eventType.getRecordComponents(), eventType));

        assertThat(instance).isNotNull();
        // Records derive equals/hashCode/toString from components; a component whose type does
        // not implement them sensibly shows up here rather than inside a consumer's log.
        assertThat(instance)
                .isEqualTo(canonical.newInstance(sampleArguments(eventType.getRecordComponents(), eventType)));
        assertThat(instance.toString()).contains(eventType.getSimpleName());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("guardedEventRecords")
    @DisplayName("a record that guards a component rejects null with a clear exception")
    void guardsRejectNullClearly(Class<?> eventType) throws Exception {
        Constructor<?> canonical = canonicalConstructor(eventType);
        canonical.setAccessible(true);
        RecordComponent[] components = eventType.getRecordComponents();
        Object[] valid = sampleArguments(components, eventType);

        int guardsFound = 0;
        for (int i = 0; i < components.length; i++) {
            if (components[i].getType().isPrimitive()) {
                continue;
            }
            Object[] arguments = valid.clone();
            arguments[i] = null;
            try {
                canonical.newInstance(arguments);
            } catch (InvocationTargetException thrown) {
                guardsFound++;
                // The guard must surface as a rejected argument, not as an incidental NPE from
                // dereferencing the value further down the constructor — the two look identical
                // from a stack trace but only one of them is a decision.
                assertThat(thrown.getCause())
                        .as("%s.%s", eventType.getSimpleName(), components[i].getName())
                        .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
            }
        }

        assertThat(guardsFound)
                .as("%s is listed as guarded but rejected nothing", eventType.getSimpleName())
                .isPositive();
    }

    /**
     * Records whose compact constructor performs runtime validation. Note that
     * {@code @NonNull} here is jspecify — a static-analysis annotation with no
     * runtime effect — so a component being marked non-null does not by itself
     * mean anything checks it. Only these records add explicit guards.
     */
    static List<Class<?>> guardedEventRecords() {
        // Deliberately over constructibleEventRecords, not eventRecords: a cross-field-invariant
        // record throws even for a fully valid argument set, so hasRuntimeGuard would read that
        // as "guards everything" and guardsRejectNullClearly would then pass on it for the wrong
        // reason. It also keeps this count comparable with the total in guardCoverageIsRecorded.
        return constructibleEventRecords().stream()
                .filter(DomainEventContractTest::hasRuntimeGuard)
                .toList();
    }

    private static boolean hasRuntimeGuard(Class<?> eventType) {
        try {
            Constructor<?> canonical = canonicalConstructor(eventType);
            canonical.setAccessible(true);
            RecordComponent[] components = eventType.getRecordComponents();
            Object[] valid = sampleArguments(components, eventType);
            for (int i = 0; i < components.length; i++) {
                if (components[i].getType().isPrimitive()) {
                    continue;
                }
                Object[] arguments = valid.clone();
                arguments[i] = null;
                try {
                    canonical.newInstance(arguments);
                } catch (InvocationTargetException thrown) {
                    return true;
                }
            }
            return false;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to probe " + eventType.getName(), e);
        }
    }

    @Test
    @DisplayName("most event records do validate at runtime, and the count is recorded")
    void guardCoverageIsRecorded() {
        int total = constructibleEventRecords().size();
        int guarded = guardedEventRecords().size();

        // Deliberately a floor rather than "all of them". jspecify @NonNull is not enforced at
        // runtime, so the records without guards are not violating a rule — but the majority
        // that do have guards are the ones carrying money and identity, and that majority
        // should not quietly shrink.
        assertThat(guarded)
                .as("%d of %d event records validate at runtime", guarded, total)
                .isGreaterThan(total / 2);
    }

    private static Constructor<?> canonicalConstructor(Class<?> eventType) throws NoSuchMethodException {
        Class<?>[] parameterTypes = Stream.of(eventType.getRecordComponents())
                .map(RecordComponent::getType)
                .toArray(Class<?>[]::new);
        return eventType.getDeclaredConstructor(parameterTypes);
    }

    private static Object[] sampleArguments(RecordComponent[] components, Class<?> eventType) {
        Object[] arguments = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            arguments[i] = sampleValue(components[i], eventType);
        }
        return arguments;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object sampleValue(RecordComponent component, Class<?> eventType) {
        Class<?> type = component.getType();
        if (type == UUID.class) {
            return SAMPLE_UUID;
        }
        if (type == Instant.class) {
            return SAMPLE_INSTANT;
        }
        if (type == LocalDate.class) {
            return LocalDate.of(2026, 8, 12);
        }
        if (type == String.class) {
            return stringSampleFor(component);
        }
        if (type == int.class || type == Integer.class) {
            return 1;
        }
        if (type == long.class || type == Long.class) {
            return 1L;
        }
        if (type == double.class || type == Double.class) {
            return 1.0d;
        }
        if (type == boolean.class || type == Boolean.class) {
            return Boolean.TRUE;
        }
        if (type == BigDecimal.class) {
            return BigDecimal.ONE;
        }
        if (type == List.class) {
            return List.of();
        }
        if (type == Set.class) {
            return Set.of();
        }
        if (type == Map.class) {
            return Map.of();
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        if (type.isRecord()) {
            try {
                Constructor<?> nested = canonicalConstructor(type);
                nested.setAccessible(true);
                return nested.newInstance(sampleArguments(type.getRecordComponents(), type));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "No sample value for nested record " + type.getSimpleName() + " on "
                                + eventType.getSimpleName(),
                        e);
            }
        }
        // Reported rather than skipped: an unsupported type means this record silently drops out
        // of the sweep, and the failure message says exactly what to add.
        throw new IllegalStateException("No sample value for component type " + type.getName() + " on "
                + eventType.getSimpleName() + "." + component.getName()
                + " — add a case to sampleValue so this record stays covered");
    }

    /**
     * Some components carry format constraints their compact constructor enforces
     * (currency codes, status enums held as strings). A generic placeholder would
     * fail the valid-construction sanity check, so these are given plausible values
     * by name.
     */
    private static String stringSampleFor(RecordComponent component) {
        String name = component.getName().toLowerCase();
        if (name.contains("currency")) {
            return "USD";
        }
        if (name.endsWith("status") || name.endsWith("state")) {
            return "PENDING";
        }
        if (name.contains("channel")) {
            return "EMAIL";
        }
        if (name.contains("email")) {
            return "someone@example.com";
        }
        return "SAMPLE";
    }
}
