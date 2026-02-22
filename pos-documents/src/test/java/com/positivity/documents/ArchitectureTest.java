package com.positivity.documents;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.UUID;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "com.positivity.documents", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private ArchitectureTest() {
    }

    private static final DescribedPredicate<JavaCall<?>> UUID_RANDOM_UUID_CALL = new DescribedPredicate<>(
            "call UUID.randomUUID()") {
        @Override
        public boolean test(JavaCall<?> input) {
            return input.getTargetOwner().isEquivalentTo(UUID.class)
                    && "randomUUID".equals(input.getName());
        }
    };

    @ArchTest
    static final ArchRule controllersShouldNotDependOnRepositoryLayer = noClasses()
            .that().resideInAPackage("..internal.controller..")
            .should().dependOnClassesThat().resideInAPackage("..internal.repository..");

    @ArchTest
    static final ArchRule packages_should_be_free_of_cycles = slices()
            .matching("com.positivity.documents.internal.(*)..")
            .should().beFreeOfCycles()
            .because("cyclic dependencies make modules harder to maintain and evolve");
    @ArchTest
    static final ArchRule entities_should_depend_on_uuidv7_generator = classes()
            .that().resideInAnyPackage("..internal.entity..", "..internal.model..")
            .and().areAnnotatedWith("jakarta.persistence.Entity")
            .should().dependOnClassesThat().haveFullyQualifiedName("com.positivity.shared.id.UUIDv7Generator")
            .allowEmptyShould(true)
            .because("ADR-0013 mandates UUID v7 generation for all entity identifiers");
    @ArchTest
    static final ArchRule entities_should_not_call_uuid_randomUUID = noClasses()
            .that().resideInAnyPackage("..internal.entity..", "..internal.model..")
            .and().areAnnotatedWith("jakarta.persistence.Entity")
            .should().callMethodWhere(UUID_RANDOM_UUID_CALL)
            .allowEmptyShould(true)
            .because("UUIDv7Generator centralizes ID creation; direct randomUUID calls are not allowed");
}
