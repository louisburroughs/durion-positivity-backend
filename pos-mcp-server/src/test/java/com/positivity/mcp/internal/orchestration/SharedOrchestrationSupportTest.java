package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.config.CurrentUserContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the caller-context block, focused on the current-date disclosure added by #1661.
 *
 * <p>Before that change nothing in the assembled prompt told the model what day it was, so every
 * relative range — "last six months", "this year" — was resolved against a date the model invented.
 * That broke four of the twelve Wave 2 analytics gate questions and, worse, made the gate
 * irreproducible: the same question asked on two days measured two different windows.
 */
class SharedOrchestrationSupportTest {

    private static final CurrentUserContext CALLER = new CurrentUserContext(
            "admin.alpha",
            UUID.fromString("01936e5b-4567-7a3d-8b6e-1a2345678901"),
            "ROLE_ADMIN",
            Set.of("ROLE_ADMIN"),
            Set.of("ROLE_ADMIN", "accounting:analytics:view"),
            Set.of("accounting:analytics:view"));

    private static SharedOrchestrationSupport supportAt(String isoInstant) {
        return new SharedOrchestrationSupport(Clock.fixed(Instant.parse(isoInstant), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("formatUserContext states the current date so ranges resolve from a supplied fact")
    void formatUserContext_statesCurrentDate() {
        String context = supportAt("2026-09-03T11:00:00Z").formatUserContext(CALLER);

        assertThat(context).contains("Today's date is 2026-09-03");
        assertThat(context).contains("Resolve every relative date range from these dates");
    }

    /**
     * The anchor the model needs for "the last six months": naming the last complete month end
     * removes a month-arithmetic step rather than leaving it to be derived.
     */
    @Test
    @DisplayName("formatUserContext names the last complete calendar month end")
    void formatUserContext_namesLastCompleteMonthEnd() {
        String context = supportAt("2026-09-03T11:00:00Z").formatUserContext(CALLER);

        assertThat(context).contains("the last complete calendar month ended 2026-08-31");
    }

    /**
     * The boundary that decides whether a period counts as complete. On the last day of a month
     * that month is still open — rows can land on it — so August is not complete until September
     * starts. Getting this wrong would silently admit a partial month into every comparison built
     * on it, which is the exact failure mode this contract exists to prevent.
     */
    @Test
    @DisplayName("a month is complete only once it has ended, so its own last day still excludes it")
    void formatUserContext_onFinalDayOfMonth_treatsThatMonthAsIncomplete() {
        String context = supportAt("2026-08-31T23:00:00Z").formatUserContext(CALLER);

        assertThat(context).contains("Today's date is 2026-08-31");
        assertThat(context).contains("the last complete calendar month ended 2026-07-31");
    }

    @Test
    @DisplayName("formatUserContext crosses a year boundary without special-casing")
    void formatUserContext_inJanuary_reportsPreviousDecember() {
        String context = supportAt("2027-01-05T00:00:00Z").formatUserContext(CALLER);

        assertThat(context).contains("the last complete calendar month ended 2026-12-31");
    }

    /** The pre-existing caller identity block must survive the addition. */
    @Test
    @DisplayName("formatUserContext still carries the caller identity and self-reference guidance")
    void formatUserContext_retainsCallerIdentity() {
        String context = supportAt("2026-09-03T11:00:00Z").formatUserContext(CALLER);

        assertThat(context).contains("username=admin.alpha").contains("primaryRole=ROLE_ADMIN");
        assertThat(context).contains("Interpret references to 'me', 'my', or 'current user'");
    }
}
