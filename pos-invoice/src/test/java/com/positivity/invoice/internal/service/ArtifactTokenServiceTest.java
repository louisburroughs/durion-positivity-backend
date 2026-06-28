package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.invoice.internal.service.ArtifactTokenService.InvalidTokenException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArtifactTokenServiceTest {

    private static final UUID INVOICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String REF = new ArtifactRef(ArtifactRef.Type.INVOICE, INVOICE).encode();
    private static final String SECRET = "test-signing-secret";

    private ArtifactTokenService service(Clock clock) {
        return new ArtifactTokenService(clock, SECRET, 300);
    }

    @Test
    void issueThenVerify_roundTrips() {
        ArtifactTokenService svc = service(fixed("2026-01-01T00:00:00Z"));
        ArtifactTokenService.IssuedToken issued = svc.issue(INVOICE, REF);

        svc.verify(issued.token(), INVOICE, REF); // no exception
        assertThat(issued.expiresAt()).isEqualTo(Instant.parse("2026-01-01T00:05:00Z"));
    }

    @Test
    void verify_rejectsTamperedSignature() {
        ArtifactTokenService svc = service(fixed("2026-01-01T00:00:00Z"));
        String token = svc.issue(INVOICE, REF).token();
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        assertThatThrownBy(() -> svc.verify(tampered, INVOICE, REF)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void verify_rejectsWrongInvoice() {
        ArtifactTokenService svc = service(fixed("2026-01-01T00:00:00Z"));
        String token = svc.issue(INVOICE, REF).token();

        assertThatThrownBy(() -> svc.verify(token, UUID.randomUUID(), REF)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void verify_rejectsWrongArtifact() {
        ArtifactTokenService svc = service(fixed("2026-01-01T00:00:00Z"));
        String token = svc.issue(INVOICE, REF).token();
        String otherRef = new ArtifactRef(ArtifactRef.Type.RECEIPT, UUID.randomUUID()).encode();

        assertThatThrownBy(() -> svc.verify(token, INVOICE, otherRef)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void verify_rejectsExpiredToken() {
        String token = service(fixed("2026-01-01T00:00:00Z")).issue(INVOICE, REF).token();
        ArtifactTokenService later = service(fixed("2026-01-01T00:05:01Z"));

        assertThatThrownBy(() -> later.verify(token, INVOICE, REF)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void verify_rejectsTokenSignedWithDifferentSecret() {
        String token =
                new ArtifactTokenService(fixed("2026-01-01T00:00:00Z"), "other-secret", 300).issue(INVOICE, REF).token();

        assertThatThrownBy(() -> service(fixed("2026-01-01T00:00:00Z")).verify(token, INVOICE, REF))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void verify_rejectsMalformedToken() {
        assertThatThrownBy(() -> service(fixed("2026-01-01T00:00:00Z")).verify("not-a-token", INVOICE, REF))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void randomKeyFallback_whenSecretBlank_stillIssuesAndVerifies() {
        ArtifactTokenService svc = new ArtifactTokenService(fixed("2026-01-01T00:00:00Z"), "", 300);
        String token = svc.issue(INVOICE, REF).token();
        svc.verify(token, INVOICE, REF); // no exception
    }

    private static Clock fixed(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    @Test
    void artifactRef_encodeDecode_roundTrips() {
        UUID id = UUID.randomUUID();
        ArtifactRef ref = new ArtifactRef(ArtifactRef.Type.RECEIPT, id);
        ArtifactRef decoded = ArtifactRef.decode(ref.encode());

        assertThat(decoded.type()).isEqualTo(ArtifactRef.Type.RECEIPT);
        assertThat(decoded.id()).isEqualTo(id);
    }

    @Test
    void artifactRef_decode_rejectsGarbage() {
        assertThatThrownBy(() -> ArtifactRef.decode("!!!not-base64!!!"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArtifactRef.decode(Duration.ZERO.toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
