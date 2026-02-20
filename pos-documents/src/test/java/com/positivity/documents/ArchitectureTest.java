package com.positivity.documents;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "com.positivity.documents")
class ArchitectureTest {

    private ArchitectureTest() {
    }

    @ArchTest
    static final ArchRule controllersShouldNotDependOnRepositoryLayer = noClasses()
            .that().resideInAPackage("..internal.controller..")
            .should().dependOnClassesThat().resideInAPackage("..internal.repository..");

    @ArchTest
    static final ArchRule packages_should_be_free_of_cycles = slices()
            .matching("com.positivity.documents.(*)..")
            .should().beFreeOfCycles()
            .because("cyclic dependencies make modules harder to maintain and evolve");
}
