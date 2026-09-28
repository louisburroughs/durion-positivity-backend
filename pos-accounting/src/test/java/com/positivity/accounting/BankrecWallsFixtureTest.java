package com.positivity.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the bank reconciliation walls of {@link ArchitectureTest} are not vacuous (SPEC §2.1, §8.4 [M];
 * #2300): each rule, run over the deliberate violations in {@code com.positivity.accounting.archfixture},
 * must fail and name the offending class. Widening or mistyping a rule's package pattern lets the fixture
 * violation through, and this test fails.
 */
@DisplayName("Bank reconciliation core/adapter walls catch violations")
class BankrecWallsFixtureTest {

    private static JavaClasses fixtures;

    @BeforeAll
    static void importFixtures() {
        fixtures = new ClassFileImporter().importPackages("com.positivity.accounting.archfixture");
    }

    @Test
    @DisplayName("the fixtures are imported (the violations below are real)")
    void fixturesArePresent() {
        assertThat(fixtures.contain(
                        com.positivity.accounting.archfixture.bankrec.service.CoreServiceReachingIntoAdapter.class))
                .isTrue();
    }

    @Test
    @DisplayName(
            "a ..bankrec.service.. class importing a ..bankfeed.file.. type fails bankrec_must_not_access_bankfeed")
    void coreReachingIntoAdapterIsCaught() {
        assertViolation(ArchitectureTest.bankrec_must_not_access_bankfeed, "CoreServiceReachingIntoAdapter");
    }

    @Test
    @DisplayName("a ..bankfeed.. class using a core service fails bankfeed_may_only_use_intake_and_dto")
    void adapterReachingPastTheIntakeIsCaught() {
        assertViolation(ArchitectureTest.bankfeed_may_only_use_intake_and_dto, "AdapterReachingIntoCoreService");
    }

    @Test
    @DisplayName("a ..bankrec.. class reading java.nio.file fails bankrec_must_not_use_file_io_or_http_clients")
    void coreReadingFilesIsCaught() {
        assertViolation(ArchitectureTest.bankrec_must_not_use_file_io_or_http_clients, "CoreServiceReadingFiles");
    }

    private static void assertViolation(ArchRule rule, String offendingClass) {
        assertThatThrownBy(() -> rule.check(fixtures))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(offendingClass);
    }
}
