package com.positivity.accounting;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;
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

    static final class ReadsTheClockZoneImplicitly {
        YearMonth month(Clock clock) {
            return YearMonth.now(clock);
        }
    }

    static final class ReadsTheJvmZoneImplicitly {
        LocalDateTime now() {
            return LocalDateTime.now();
        }
    }

    static final class ReadsTheJvmTimeZone {
        TimeZone zone() {
            return TimeZone.getDefault();
        }
    }

    static final class StatesItsZone {
        LocalDate date(Instant instant) {
            return LocalDate.ofInstant(instant, ZoneOffset.UTC);
        }

        LocalDate today(Clock clock) {
            return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        }

        Instant now(Clock clock) {
            return Instant.now(clock);
        }
    }

    private static boolean violates(Class<?> type) {
        // Only the fixture itself: no classpath resolution (archunit.properties), so this import stays a few classes.
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importClasses(type);
        return noClasses()
                .should()
                .callMethodWhere(ArchitectureTest.GUESSED_ZONE_CALL)
                .evaluate(classes)
                .hasViolation();
    }

    @Test
    @DisplayName("Clock.getZone(), ZoneId.systemDefault(), YearMonth.now(clock), LocalDateTime.now() and"
            + " TimeZone.getDefault() violate; an explicit ZoneOffset.UTC and Instant.now(clock) do not")
    void catchesGuessedZones() {
        assertThat(violates(ReadsTheClockZone.class)).isTrue();
        assertThat(violates(ReadsTheJvmZone.class)).isTrue();
        assertThat(violates(ReadsTheClockZoneImplicitly.class)).isTrue();
        assertThat(violates(ReadsTheJvmZoneImplicitly.class)).isTrue();
        assertThat(violates(ReadsTheJvmTimeZone.class)).isTrue();
        assertThat(violates(StatesItsZone.class)).isFalse();
    }
}
