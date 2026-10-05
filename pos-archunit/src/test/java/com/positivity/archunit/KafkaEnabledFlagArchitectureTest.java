package com.positivity.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.members;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.archunit.fixture.kafkaenabled.FixtureClassPrefixNameGate;
import com.positivity.archunit.fixture.kafkaenabled.FixtureClassValueGate;
import com.positivity.archunit.fixture.kafkaenabled.FixtureMethodPrefixNameGate;
import com.positivity.archunit.fixture.kafkaenabled.FixtureMethodValueGate;
import com.positivity.archunit.fixture.kafkaenabled.FixtureUnrelatedGate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-0044 §4 (#2195, #2463): Kafka is tier-1 infrastructure, so no bean may be gated on a
 * {@code ...kafka.enabled} property. The gate is {@code @KafkaRails} (pos-kafka-common).
 *
 * <p>{@link #NOT_YET_CONVERTED} freezes the modules that still carry the retired flag. It may only
 * shrink: converting a module removes its entry, and the rule then covers it. Never add a module.
 */
@AnalyzeClasses(packages = "com.positivity", importOptions = ImportOption.DoNotIncludeTests.class)
class KafkaEnabledFlagArchitectureTest {

    private static final String CONDITIONAL_ON_PROPERTY =
            "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty";

    /**
     * Root packages of modules whose {@code kafka.enabled} opt-in is not yet retired (#2463 PRs 2 and
     * 3 delete entries; the end state is an empty set).
     */
    static final Set<String> NOT_YET_CONVERTED = Set.of(
            "com.positivity.workorder",
            "com.positivity.order",
            "com.positivity.invoice",
            "com.positivity.location",
            "com.positivity.customer",
            "com.positivity.catalog",
            "com.positivity.shopmanager",
            "com.positivity.people",
            "com.positivity.securityservice",
            "com.positivity.warranty",
            "com.positivity.platformsender",
            "com.positivity.marketing",
            "com.positivity.vehicle",
            "com.positivity.peoplecontact",
            "com.positivity.tenant",
            "com.positivity.supplier",
            "com.positivity.mcp");

    @ArchTest
    static final ArchRule NO_KAFKA_ENABLED_ON_CLASSES = classes()
            .should(new ArchCondition<JavaClass>("not be gated on a kafka.enabled property") {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                    report(type, type, type.getName(), events);
                }
            })
            .as("no class gated by @ConditionalOnProperty on a ...kafka.enabled key outside NOT_YET_CONVERTED "
                    + "(ADR-0044 §4)");

    @ArchTest
    static final ArchRule NO_KAFKA_ENABLED_ON_MEMBERS = members()
            .should(new ArchCondition<JavaMember>("not be gated on a kafka.enabled property") {
                @Override
                public void check(JavaMember member, ConditionEvents events) {
                    report(member, member.getOwner(), member.getFullName(), events);
                }
            })
            .as("no method gated by @ConditionalOnProperty on a ...kafka.enabled key outside NOT_YET_CONVERTED "
                    + "(ADR-0044 §4)");

    private static void report(HasAnnotations<?> target, JavaClass owner, String where, ConditionEvents events) {
        if (isAllowlisted(owner)) {
            return;
        }
        target.tryGetAnnotationOfType(CONDITIONAL_ON_PROPERTY).stream()
                .flatMap(annotation -> keysOf(annotation).stream())
                .filter(KafkaEnabledFlagArchitectureTest::endsWithKafkaEnabled)
                .forEach(key -> events.add(SimpleConditionEvent.violated(
                        target, where + " is gated on retired flag '" + key + "'; use @KafkaRails")));
    }

    private static boolean isAllowlisted(JavaClass owner) {
        String pkg = owner.getPackageName();
        return NOT_YET_CONVERTED.stream().anyMatch(root -> pkg.equals(root) || pkg.startsWith(root + "."));
    }

    /** Full property keys: {@code prefix + "." + name}, over both {@code value} and {@code name}. */
    static List<String> keysOf(JavaAnnotation<?> annotation) {
        String prefix = annotation.get("prefix").map(Object::toString).orElse("");
        List<String> names = new ArrayList<>();
        for (String attribute : new String[] {"value", "name"}) {
            annotation.get(attribute).ifPresent(v -> {
                if (v instanceof Object[] array) {
                    for (Object o : array) {
                        names.add(o.toString());
                    }
                }
            });
        }
        List<String> keys = new ArrayList<>();
        for (String name : names) {
            keys.add(prefix.isEmpty() ? name : prefix + (prefix.endsWith(".") ? "" : ".") + name);
        }
        return keys;
    }

    static boolean endsWithKafkaEnabled(String key) {
        return key.endsWith("kafka.enabled");
    }

    private static void assertRejected(Class<?> fixture, String expectedMessagePart) {
        var imported = new ClassFileImporter().importClasses(fixture);
        assertThatThrownBy(() -> {
                    NO_KAFKA_ENABLED_ON_CLASSES.check(imported);
                    NO_KAFKA_ENABLED_ON_MEMBERS.check(imported);
                })
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(expectedMessagePart)
                .hasMessageContaining("pos.fixture.kafka");
    }

    @Test
    @DisplayName("class-level prefix + name ...kafka.enabled is rejected")
    void classPrefixNameRejected() {
        assertRejected(FixtureClassPrefixNameGate.class, "FixtureClassPrefixNameGate");
    }

    @Test
    @DisplayName("class-level shorthand value ...kafka.enabled is rejected")
    void classValueRejected() {
        assertRejected(FixtureClassValueGate.class, "FixtureClassValueGate");
    }

    @Test
    @DisplayName("method-level prefix + name ...kafka.enabled is rejected")
    void methodPrefixNameRejected() {
        assertRejected(FixtureMethodPrefixNameGate.class, "gatedByPrefixAndName");
    }

    @Test
    @DisplayName("method-level shorthand value ...kafka.enabled is rejected")
    void methodValueRejected() {
        assertRejected(FixtureMethodValueGate.class, "gatedByValue");
    }

    @Test
    @DisplayName("an unrelated property gate passes both rules")
    void unrelatedPropertyPasses() {
        var imported = new ClassFileImporter().importClasses(FixtureUnrelatedGate.class);
        assertThatCode(() -> {
                    NO_KAFKA_ENABLED_ON_CLASSES.check(imported);
                    NO_KAFKA_ENABLED_ON_MEMBERS.check(imported);
                })
                .doesNotThrowAnyException();
    }
}
