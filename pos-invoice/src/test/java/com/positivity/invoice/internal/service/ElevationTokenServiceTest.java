package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ElevationTokenService} — HMAC mint/verify, expiry, and invoice
 * scoping. This is the security boundary that replaced the previous "any non-blank code"
 * acceptance, so the negative cases matter as much as the happy path.
 */
class ElevationTokenServiceTest {

    private static final String SECRET = "test-elevation-secret-please-rotate";
    private static final Instant NOW = Instant.parse("2026-06-29T12:00:00Z");

    private ElevationTokenService service(Clock clock) {
        return new ElevationTokenService(clock, SECRET, 300);
    }

    private static Clock fixedClock(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    @Test
    void mintThenVerify_returnsManagerPersonId_whenScopeAndTimeValid() {
        ElevationTokenService service = service(fixedClock(NOW));
        UUID invoiceId = UUID.randomUUID();
        UUID managerPersonId = UUID.randomUUID();

        String token = service.mint(invoiceId, managerPersonId).token();

        assertThat(service.verify(token, invoiceId)).contains(managerPersonId);
    }

    @Test
    void verify_returnsEmpty_whenInvoiceScopeDiffers() {
        ElevationTokenService service = service(fixedClock(NOW));
        UUID invoiceId = UUID.randomUUID();
        String token = service.mint(invoiceId, UUID.randomUUID()).token();

        assertThat(service.verify(token, UUID.randomUUID())).isEmpty();
    }

    @Test
    void verify_returnsEmpty_whenExpired() {
        UUID invoiceId = UUID.randomUUID();
        UUID managerPersonId = UUID.randomUUID();
        // Mint at NOW (300s TTL), verify 301s later.
        String token = service(fixedClock(NOW)).mint(invoiceId, managerPersonId).token();
        ElevationTokenService later = service(fixedClock(NOW.plus(Duration.ofSeconds(301))));

        assertThat(later.verify(token, invoiceId)).isEmpty();
    }

    @Test
    void verify_returnsEmpty_whenSignedWithDifferentSecret() {
        UUID invoiceId = UUID.randomUUID();
        String token = service(fixedClock(NOW)).mint(invoiceId, UUID.randomUUID()).token();
        ElevationTokenService otherSecret =
                new ElevationTokenService(fixedClock(NOW), "a-different-secret-of-sufficient-length", 300);

        assertThat(otherSecret.verify(token, invoiceId)).isEmpty();
    }

    @Test
    void verify_returnsEmpty_whenPayloadTampered() {
        ElevationTokenService service = service(fixedClock(NOW));
        UUID invoiceId = UUID.randomUUID();
        String token = service.mint(invoiceId, UUID.randomUUID()).token();
        // Flip a character in the payload segment.
        String tampered = (token.charAt(0) == 'A' ? 'B' : 'A') + token.substring(1);

        assertThat(service.verify(tampered, invoiceId)).isEmpty();
    }

    @Test
    void verify_returnsEmpty_forMalformedOrBlankToken() {
        ElevationTokenService service = service(fixedClock(NOW));
        UUID invoiceId = UUID.randomUUID();

        assertThat(service.verify("", invoiceId)).isEmpty();
        assertThat(service.verify("not-a-token", invoiceId)).isEmpty();
        assertThat(service.verify("only.onedot", invoiceId)).isEmpty();
    }

    @Test
    void mint_includesExpiryAtTtlBoundary() {
        ElevationTokenService service = service(fixedClock(NOW));
        Instant expiresAt = service.mint(UUID.randomUUID(), UUID.randomUUID()).expiresAt();

        assertThat(expiresAt).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    void verify_returnsEmpty_whenSecretMissing() {
        ElevationTokenService noSecret = new ElevationTokenService(fixedClock(NOW), "", 300);
        // Both mint and verify require a configured secret; verify on a random string fails fast.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> noSecret.verify("anything", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void verify_failsFast_whenSecretTooShort() {
        ElevationTokenService shortSecret = new ElevationTokenService(fixedClock(NOW), "too-short", 300);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> shortSecret.verify("anything", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }
}
