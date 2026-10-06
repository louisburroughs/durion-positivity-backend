package com.positivity.accounting;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Proves {@link ArchitectureTest#GUESSED_ZONE_CALL} catches both guessed zones and passes an explicit one (#2558). */
@DisplayName("ArchUnit: a guessed zone is caught (#2558)")
class AccountingCalendarZoneRuleTest {

    static final class ReadsTheClockZone {
        LocalDate date(Clock clock, Instant instant) {
            return LocalDate.ofInstant(instant, clock.getZone());
        }
    }

    static final class ReadsTheJvmZone {
        LocalDate date(Instant instant) {
            return LocalDate.ofInstant(instant, ZoneId.systemDefault());
        }
    }

    static final class StatesItsZone {
        LocalDate date(Instant instant) {
            return LocalDate.ofInstant(instant, ZoneOffset.UTC);
        }
    }

    private static boolean violates(Class<?> type) {
        JavaClasses classes = new ClassFileImporter().importClasses(type);
        return noClasses()
                .should()
                .callMethodWhere(ArchitectureTest.GUESSED_ZONE_CALL)
                .evaluate(classes)
                .hasViolation();
    }

    @Test
    @DisplayName("Clock.getZone() and ZoneId.systemDefault() violate; an explicit ZoneOffset.UTC does not")
    void catchesGuessedZones() {
        assertThat(violates(ReadsTheClockZone.class)).isTrue();
        assertThat(violates(ReadsTheJvmZone.class)).isTrue();
        assertThat(violates(StatesItsZone.class)).isFalse();
    }
}
