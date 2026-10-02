package com.positivity.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.archunit.fixture.consumerretry.FixtureCompliantListener;
import com.positivity.archunit.fixture.consumerretry.FixtureSwallowingListener;
import com.positivity.archunit.fixture.consumerretry.FixtureTransientOnlyListener;
import com.positivity.tenancy.kafka.RetryableConsumerFailures;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.PackageMatcher;
import com.tngtech.archunit.core.domain.TryCatchBlock;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-0044 §4 (amended 2026-10-02, #2355): the set of failures a Kafka consumer rethrows for
 * container retry has one definition, {@link RetryableConsumerFailures}, and every consumer that
 * handles a failure itself asks it first.
 *
 * <p>The first rule covers every class that declares a {@code @KafkaListener} method and every
 * class one of those calls directly (the handler a dispatching listener delegates to, which
 * carries the catch on its behalf). The second covers the listener classes only: the classes a
 * listener calls include outbox writers and fact publishers, whose catches are the producer's.
 *
 * <p>Both rules read bytecode, so they judge the shape, not the intent: they cannot see whether
 * the {@code isRetryable} call sits in the right catch, or ahead of the processed mark. The
 * per-module propagation tests pin that.
 */
@AnalyzeClasses(packages = "com.positivity", importOptions = ImportOption.DoNotIncludeTests.class)
class KafkaConsumerRetryArchitectureTest {

    private static final String KAFKA_LISTENER = "org.springframework.kafka.annotation.KafkaListener";
    private static final String TRANSIENT = "org.springframework.dao.TransientDataAccessException";

    /** Catch types wide enough to swallow a retryable failure. */
    private static final Set<String> BROAD_CATCHES = Set.of(
            "java.lang.Throwable",
            "java.lang.Exception",
            "java.lang.RuntimeException",
            "org.springframework.core.NestedRuntimeException",
            "org.springframework.dao.DataAccessException");

    /**
     * Owners of calls that cannot fail retryably: a {@code try} that only parses JSON, hands a
     * record to Kafka, logs or counts has nothing for the container to retry. That is the shape of
     * the "skip an unparsable message" guard and of the reconciliation-manifest listeners' two
     * catches (#2354). Any other call in the {@code try} (a repository, a service, a transaction
     * template, one of the class's own methods) makes the catch one that must classify. Field reads
     * and constructor calls are not counted at all.
     */
    private static final String[] INERT_CALL_OWNERS = {
        "java..",
        "tools.jackson..",
        "com.fasterxml.jackson..",
        "org.slf4j..",
        "io.micrometer..",
        "org.apache.kafka..",
        "org.springframework.kafka..",
        "com.positivity.tenancy..",
        "com.positivity.domainevents.."
    };

    private static final DescribedPredicate<JavaClass> KAFKA_LISTENER_CLASSES =
            new DescribedPredicate<>("classes with a @KafkaListener method") {
                @Override
                public boolean test(JavaClass clazz) {
                    return declaresListener(clazz);
                }
            };

    private static final DescribedPredicate<JavaClass> KAFKA_CONSUMER_CLASSES =
            new DescribedPredicate<>("Kafka consumer classes (a @KafkaListener class or a class it calls directly)") {
                @Override
                public boolean test(JavaClass clazz) {
                    if (clazz.getPackageName().startsWith("com.positivity.tenancy")) {
                        return false;
                    }
                    return declaresListener(clazz)
                            || clazz.getDirectDependenciesToSelf().stream()
                                    .anyMatch(dependency -> declaresListener(dependency.getOriginClass()));
                }
            };

    @ArchTest
    static final ArchRule consumers_do_not_single_out_the_transient_type = consumerCodeUnits()
            .should(notCatch(TRANSIENT))
            .because("ADR-0044 section 4 (amended 2026-10-02, #2355): a consumer that rethrows only"
                    + " TransientDataAccessException treats a dropped connection"
                    + " (DataAccessResourceFailureException) or a transaction that could not open"
                    + " (CannotCreateTransactionException) as permanent and swallows or marks it; ask"
                    + " RetryableConsumerFailures.isRetryable(e) at the head of the permanent catch instead");

    @ArchTest
    static final ArchRule consumers_classify_before_they_swallow = codeUnits()
            .that()
            .areDeclaredInClassesThat(KAFKA_LISTENER_CLASSES)
            .should(classifyWhatItCatchesBroadly())
            .because("ADR-0044 section 4 (amended 2026-10-02, #2355): a consumer that catches a handler"
                    + " failure rethrows the retryable set before it logs, records or marks anything, so the"
                    + " container retries with backoff and dead-letters; call"
                    + " RetryableConsumerFailures.isRetryable(e) first and rethrow on true");

    private static com.tngtech.archunit.lang.syntax.elements.GivenCodeUnitsConjunction<?> consumerCodeUnits() {
        return codeUnits().that().areDeclaredInClassesThat(KAFKA_CONSUMER_CLASSES);
    }

    private static boolean declaresListener(JavaClass clazz) {
        return clazz.getMethods().stream().anyMatch(method -> method.isAnnotatedWith(KAFKA_LISTENER));
    }

    private static ArchCondition<JavaCodeUnit> notCatch(String throwableType) {
        return new ArchCondition<>("not catch " + throwableType) {
            @Override
            public void check(JavaCodeUnit codeUnit, ConditionEvents events) {
                for (TryCatchBlock block : codeUnit.getTryCatchBlocks()) {
                    if (block.getCaughtThrowables().stream()
                            .anyMatch(caught -> caught.getName().equals(throwableType))) {
                        events.add(SimpleConditionEvent.violated(
                                codeUnit,
                                codeUnit.getFullName() + " catches " + throwableType + " in "
                                        + block.getSourceCodeLocation()));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaCodeUnit> classifyWhatItCatchesBroadly() {
        return new ArchCondition<>(
                "call RetryableConsumerFailures.isRetryable when a broad catch guards more than parsing or sending") {
            @Override
            public void check(JavaCodeUnit codeUnit, ConditionEvents events) {
                if (callsClassifier(codeUnit)) {
                    return;
                }
                for (TryCatchBlock block : codeUnit.getTryCatchBlocks()) {
                    if (catchesBroadly(block) && guardsRetryableWork(block)) {
                        events.add(SimpleConditionEvent.violated(
                                codeUnit,
                                codeUnit.getFullName() + " catches "
                                        + block.getCaughtThrowables().stream()
                                                .map(JavaClass::getSimpleName)
                                                .sorted()
                                                .toList()
                                        + " around work that can fail retryably, without asking"
                                        + " RetryableConsumerFailures.isRetryable, in "
                                        + block.getSourceCodeLocation()));
                    }
                }
            }
        };
    }

    private static boolean callsClassifier(JavaCodeUnit codeUnit) {
        return codeUnit.getMethodCallsFromSelf().stream()
                .anyMatch(call -> call.getTargetOwner().isEquivalentTo(RetryableConsumerFailures.class)
                        && call.getName().equals("isRetryable"));
    }

    private static boolean catchesBroadly(TryCatchBlock block) {
        return block.getCaughtThrowables().stream().anyMatch(caught -> BROAD_CATCHES.contains(caught.getName()));
    }

    private static boolean guardsRetryableWork(TryCatchBlock block) {
        // Method calls only: reading a field (the logger, the object mapper) cannot fail, and a
        // constructor here builds a payload record or an exception, not a query.
        return block.getAccessesContainedInTryBlock().stream()
                .filter(JavaMethodCall.class::isInstance)
                .anyMatch(access -> !isInert(access));
    }

    private static boolean isInert(JavaAccess<?> access) {
        String ownerPackage = access.getTargetOwner().getPackageName();
        for (String inert : INERT_CALL_OWNERS) {
            if (PackageMatcher.of(inert).matches(ownerPackage)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("the pre-#2355 shape (rethrow TransientDataAccessException only) fails the first rule")
    void transientOnlyConsumerIsRejected() {
        assertThatThrownBy(() -> consumers_do_not_single_out_the_transient_type.check(
                        new ClassFileImporter().importClasses(FixtureTransientOnlyListener.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("FixtureTransientOnlyListener")
                .hasMessageContaining("TransientDataAccessException");
    }

    @Test
    @DisplayName("a consumer that swallows a handler failure without classifying it fails the second rule")
    void swallowingConsumerIsRejected() {
        assertThatThrownBy(() -> consumers_classify_before_they_swallow.check(
                        new ClassFileImporter().importClasses(FixtureSwallowingListener.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("FixtureSwallowingListener.onEvent")
                .hasMessageNotContaining("FixtureSwallowingListener.parseOnly");
    }

    @Test
    @DisplayName("the #2355 shape passes both rules")
    void compliantConsumerPasses() {
        var compliant = new ClassFileImporter().importClasses(FixtureCompliantListener.class);

        assertThatCode(() -> consumers_do_not_single_out_the_transient_type.check(compliant))
                .doesNotThrowAnyException();
        assertThatCode(() -> consumers_classify_before_they_swallow.check(compliant))
                .doesNotThrowAnyException();
    }
}
