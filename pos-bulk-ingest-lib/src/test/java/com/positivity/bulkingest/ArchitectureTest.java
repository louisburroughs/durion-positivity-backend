package com.positivity.bulkingest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ArchUnit tests for pos-bulk-ingest-lib.
 *
 * <p>
 * This is a shared library and must remain free of Spring Security coupling
 * so it can be embedded in any service without pulling in security
 * infrastructure.
 * </p>
 */
@AnalyzeClasses(packages = "com.positivity.bulkingest", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

        private ArchitectureTest() {
                // Utility class
        }

        @ArchTest
        static final ArchRule library_should_not_import_spring_security = noClasses()
                        .that()
                        .resideInAPackage("com.positivity.bulkingest..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAPackage("org.springframework.security..")
                        .allowEmptyShould(true)
                        .because("pos-bulk-ingest-lib is a shared library and must not carry Spring Security coupling");

        @ArchTest
        static final ArchRule library_should_not_import_spring_batch = noClasses()
                        .that()
                        .resideInAPackage("com.positivity.bulkingest..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAPackage("org.springframework.batch..")
                        .allowEmptyShould(true)
                        .because("pos-bulk-ingest-lib is a shared library and must not carry Spring Batch coupling");
}
