package com.positivity.documents;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.positivity.documents")
class ArchitectureTest {

    private ArchitectureTest() {
    }

    @ArchTest
    static final ArchRule controllersShouldNotDependOnRepositoryLayer = noClasses()
            .that().resideInAPackage("..internal.controller..")
            .should().dependOnClassesThat().resideInAPackage("..internal.repository..");
}
