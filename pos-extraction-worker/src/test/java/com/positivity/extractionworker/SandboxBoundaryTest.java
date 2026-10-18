package com.positivity.extractionworker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** The boundary rule really fails when web-side code calls the child's entry point. */
class SandboxBoundaryTest {

    @Test
    void ruleFailsOnAFixtureThatParsesInProcess() {
        JavaClasses fixture = new ClassFileImporter().importPackages("com.positivity.extractionworker.fixture");

        assertThat(fixture).isNotEmpty();
        assertThatThrownBy(() ->
                        SandboxBoundary.webProcessUsesOnlyTheBoundaryRecords().check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ParseMain");
    }

    @Test
    void ruleHoldsForTheRealWebProcess() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.positivity.extractionworker");

        SandboxBoundary.webProcessUsesOnlyTheBoundaryRecords().check(production);
    }
}
