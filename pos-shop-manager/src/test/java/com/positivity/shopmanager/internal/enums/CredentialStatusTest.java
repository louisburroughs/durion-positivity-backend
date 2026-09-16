package com.positivity.shopmanager.internal.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CAP-328 D7: expiry is judged against the date asked about; the owner's terminal statuses stand. */
class CredentialStatusTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-09-16");

    @Test
    @DisplayName("held through the expiry date itself, expired the day after")
    void expiryIsInclusiveOfTheLastDay() {
        assertThat(CredentialStatus.effective("ACTIVE", TODAY, TODAY)).isEqualTo(CredentialStatus.ACTIVE);
        assertThat(CredentialStatus.effective("ACTIVE", TODAY.minusDays(1), TODAY))
                .isEqualTo(CredentialStatus.EXPIRED);
        assertThat(CredentialStatus.effective("ACTIVE", TODAY.plusDays(1), TODAY))
                .isEqualTo(CredentialStatus.ACTIVE);
    }

    @Test
    @DisplayName("no expiry never expires")
    void nullExpiryNeverExpires() {
        assertThat(CredentialStatus.effective("ACTIVE", null, TODAY)).isEqualTo(CredentialStatus.ACTIVE);
        assertThat(CredentialStatus.effective("ACTIVE", null, LocalDate.parse("2099-01-01")))
                .isEqualTo(CredentialStatus.ACTIVE);
    }

    @Test
    @DisplayName("the feed's EXPIRED is not trusted: a still-valid date reads ACTIVE, a past date EXPIRED")
    void feedExpiryIsRecomputed() {
        assertThat(CredentialStatus.effective("EXPIRED", TODAY.plusYears(1), TODAY))
                .isEqualTo(CredentialStatus.ACTIVE);
        assertThat(CredentialStatus.effective("ACTIVE", TODAY.minusYears(1), TODAY))
                .isEqualTo(CredentialStatus.EXPIRED);
    }

    @Test
    @DisplayName("REVOKED and SUPERSEDED are the owner's decisions and stand regardless of dates")
    void terminalStatusesStand() {
        assertThat(CredentialStatus.effective("REVOKED", null, TODAY)).isEqualTo(CredentialStatus.REVOKED);
        assertThat(CredentialStatus.effective("REVOKED", TODAY.plusYears(1), TODAY))
                .isEqualTo(CredentialStatus.REVOKED);
        assertThat(CredentialStatus.effective("SUPERSEDED", TODAY.minusYears(1), TODAY))
                .isEqualTo(CredentialStatus.SUPERSEDED);
    }

    @Test
    void onlyActiveIsHeld() {
        assertThat(CredentialStatus.ACTIVE.isHeld()).isTrue();
        assertThat(CredentialStatus.EXPIRED.isHeld()).isFalse();
        assertThat(CredentialStatus.REVOKED.isHeld()).isFalse();
        assertThat(CredentialStatus.SUPERSEDED.isHeld()).isFalse();
    }
}
