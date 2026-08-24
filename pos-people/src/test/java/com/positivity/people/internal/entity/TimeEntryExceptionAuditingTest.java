package com.positivity.people.internal.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.people.internal.config.JpaAuditingConfig;
import com.positivity.people.internal.enums.ExceptionSeverity;
import com.positivity.people.internal.enums.ExceptionStatus;
import com.positivity.people.internal.repository.TimeEntryExceptionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * {@code time_entry_exception} audit timestamps: what is stamped, and by which path.
 *
 * <h2>Why this test exists</h2>
 *
 * {@link TimeEntryException#setCreatedAt} and {@link TimeEntryException#setUpdatedAt} were declared
 * with empty bodies, so they accepted an argument and discarded it (Sonar {@code java:S1186}).
 *
 * <p>The obvious worry was that this broke auditing — the entity is
 * {@code @EntityListeners(AuditingEntityListener.class)} with {@code @CreatedDate} and
 * {@code @LastModifiedDate}, and Spring Data writes audit values through a property accessor. It
 * does not: {@link #persistedExceptionCarriesAuditTimestamps} passes against the pre-fix entity,
 * because the accessor reaches these fields directly rather than through their setters. That test
 * is kept precisely to record it — the stamping does not depend on the setters, and a future change
 * that made it depend on them would be caught here.
 *
 * <p>What the empty bodies really were is a trap for callers. No production code called either
 * setter, so nothing was broken today; anything that started to would have silently got nothing
 * back. {@link #settersAssign} is the assertion that fails against the pre-fix entity.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_people_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            // Flyway off, schema from the entities: pos-people's baseline uses Postgres-only SQL
            // (SPLIT_PART), which H2 cannot run. This is the module's documented default test
            // footing — see FlywayMigrationIT, which covers the migrations against real Postgres.
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, TimeEntryExceptionAuditingTest.FixedClockConfig.class})
@DisplayName("TimeEntryException — audit stamping, and setters that actually assign")
class TimeEntryExceptionAuditingTest {

    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private TimeEntryExceptionRepository repository;

    @Test
    @DisplayName("a persisted exception is stamped with createdAt and updatedAt")
    void persistedExceptionCarriesAuditTimestamps() {
        TimeEntryException saved = repository.saveAndFlush(exception());

        // Passes against the pre-fix entity too. That is the point: it pins that the stamping goes
        // around the setters rather than through them, which is what made the empty bodies a latent
        // trap rather than a live outage.
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("the setters assign rather than discard")
    void settersAssign() {
        // The actual defect: a setter that accepts a value and discards it. Fails pre-fix.
        TimeEntryException entity = exception();
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);

        assertThat(entity.getCreatedAt()).isEqualTo(NOW);
        assertThat(entity.getUpdatedAt()).isEqualTo(NOW);
    }

    private static TimeEntryException exception() {
        TimeEntryException entity = new TimeEntryException();
        entity.setEmployeeId("EMP-1");
        entity.setWorkDate(LocalDate.parse("2026-08-24"));
        entity.setExceptionCode("MISSING_PUNCH");
        entity.setSeverity(ExceptionSeverity.WARNING);
        entity.setStatus(ExceptionStatus.OPEN);
        return entity;
    }
}
