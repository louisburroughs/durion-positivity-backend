package com.positivity.supplier.internal.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.exception.PayloadUnreadableException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * AES-256-GCM envelope behaviour for exchange-audit payloads (ADR-0050 §7, binding decision 1).
 *
 * <p>The assertions that matter are the negative ones. Encryption that round-trips is easy; what
 * protects a commercial audit trail is that a modified ciphertext is <em>detected</em> rather than
 * silently decrypted to garbage, that a rewritten key id fails the tag, that nonces never repeat,
 * and that a real deployment refuses to start without a key instead of writing payloads nobody can
 * read back.
 */
class AuditPayloadCipherTest {

    private static final String KEY_A = Base64.getEncoder().encodeToString(fixedKey((byte) 0x11));
    private static final String KEY_B = Base64.getEncoder().encodeToString(fixedKey((byte) 0x22));
    private static final String PAYLOAD = "<StockInquiry><Article>225/45R17</Article></StockInquiry>";

    private static byte[] fixedKey(byte fill) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, fill);
        return key;
    }

    private static AuditPayloadCipher cipher(String activeKey, String keyId, String previousKeys, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new AuditPayloadCipher(environment, activeKey, keyId, previousKeys);
    }

    private static AuditPayloadCipher devCipher() {
        return cipher(KEY_A, "k1", "", "test");
    }

    // ── Round trip ──────────────────────────────────────────────────────────────────

    @Nested
    class RoundTrip {

        @Test
        void encryptsAndDecryptsBackToTheSamePayload() {
            AuditPayloadCipher cipher = devCipher();

            assertThat(cipher.decrypt(cipher.encrypt(PAYLOAD))).isEqualTo(PAYLOAD);
        }

        @Test
        void ciphertextDoesNotContainThePlaintext() {
            AuditPayloadCipher cipher = devCipher();

            byte[] envelope = cipher.encrypt(PAYLOAD);

            assertThat(new String(envelope, StandardCharsets.ISO_8859_1))
                    .as("the whole point: the payload must not be readable in the stored bytes")
                    .doesNotContain("225/45R17")
                    .doesNotContain("StockInquiry");
        }

        @Test
        void handlesEmptyAndUnicodeAndLargePayloads() {
            AuditPayloadCipher cipher = devCipher();
            String unicode = "Größe 225 — ÿ €  \u0000 embedded null";
            String large = "x".repeat(500_000);

            assertThat(cipher.decrypt(cipher.encrypt(""))).isEmpty();
            assertThat(cipher.decrypt(cipher.encrypt(unicode))).isEqualTo(unicode);
            assertThat(cipher.decrypt(cipher.encrypt(large))).isEqualTo(large);
        }

        @Test
        void envelopeStartsWithTheVersionAndKeyIdHeader() {
            AuditPayloadCipher cipher = devCipher();

            byte[] envelope = cipher.encrypt(PAYLOAD);

            assertThat(envelope[0]).isEqualTo(AuditPayloadCipher.ENVELOPE_VERSION);
            assertThat(envelope[1]).isEqualTo((byte) 2); // "k1"
            assertThat(new String(envelope, 2, 2, StandardCharsets.UTF_8)).isEqualTo("k1");
        }
    }

    // ── Nonce discipline ────────────────────────────────────────────────────────────

    @Nested
    class NonceDiscipline {

        /**
         * GCM nonce reuse under one key leaks the XOR of the plaintexts and enables forgery, so this
         * is a correctness requirement rather than a nicety. Same payload, same key, many times: the
         * ciphertexts must all differ.
         */
        @Test
        void neverReusesANonceForTheSameKeyAndPayload() {
            AuditPayloadCipher cipher = devCipher();
            Set<String> nonces = new HashSet<>();
            Set<String> ciphertexts = new HashSet<>();

            for (int i = 0; i < 500; i++) {
                byte[] envelope = cipher.encrypt(PAYLOAD);
                int headerLength = 2 + (envelope[1] & 0xFF);
                nonces.add(Base64.getEncoder()
                        .encodeToString(java.util.Arrays.copyOfRange(
                                envelope, headerLength, headerLength + AuditPayloadCipher.NONCE_LENGTH)));
                ciphertexts.add(Base64.getEncoder().encodeToString(envelope));
            }

            assertThat(nonces).as("every encryption must draw a fresh nonce").hasSize(500);
            assertThat(ciphertexts)
                    .as("identical plaintext must never produce identical ciphertext")
                    .hasSize(500);
        }
    }

    // ── Tamper detection ────────────────────────────────────────────────────────────

    @Nested
    class TamperDetection {

        @Test
        void aFlippedCiphertextBitIsDetectedNotSilentlyDecrypted() {
            AuditPayloadCipher cipher = devCipher();
            byte[] envelope = cipher.encrypt(PAYLOAD);
            envelope[envelope.length - 1] ^= 0x01;

            assertThatThrownBy(() -> cipher.decrypt(envelope))
                    .isInstanceOf(PayloadUnreadableException.class)
                    .hasFieldOrPropertyWithValue("code", PayloadUnreadableException.AUTHENTICATION_FAILED);
        }

        /**
         * The reason the header is passed as AAD. Without it a key id could be rewritten in place and
         * the failure would read as ordinary misconfiguration instead of tampering.
         */
        @Test
        void rewritingTheKeyIdInTheHeaderFailsAuthentication() {
            AuditPayloadCipher cipher = cipher(KEY_A, "k1", "k2:" + KEY_A, "test");
            byte[] envelope = cipher.encrypt(PAYLOAD);
            // "k1" -> "k2": a configured key id, so this is NOT an unknown-key failure.
            envelope[3] = (byte) '2';

            assertThatThrownBy(() -> cipher.decrypt(envelope))
                    .isInstanceOf(PayloadUnreadableException.class)
                    .hasFieldOrPropertyWithValue("code", PayloadUnreadableException.AUTHENTICATION_FAILED);
        }

        @Test
        void aFlippedNonceBitIsDetected() {
            AuditPayloadCipher cipher = devCipher();
            byte[] envelope = cipher.encrypt(PAYLOAD);
            envelope[2 + 2] ^= 0x01;

            assertThatThrownBy(() -> cipher.decrypt(envelope))
                    .isInstanceOf(PayloadUnreadableException.class)
                    .hasFieldOrPropertyWithValue("code", PayloadUnreadableException.AUTHENTICATION_FAILED);
        }

        @Test
        void theWrongKeyFailsAuthenticationRatherThanReturningGarbage() {
            byte[] envelope = cipher(KEY_A, "k1", "", "test").encrypt(PAYLOAD);

            assertThatThrownBy(() -> cipher(KEY_B, "k1", "", "test").decrypt(envelope))
                    .isInstanceOf(PayloadUnreadableException.class)
                    .hasFieldOrPropertyWithValue("code", PayloadUnreadableException.AUTHENTICATION_FAILED);
        }
    }

    // ── Malformed envelopes ─────────────────────────────────────────────────────────

    @Nested
    class MalformedEnvelopes {

        @Test
        void rejectsAnUnsupportedVersionByte() {
            AuditPayloadCipher cipher = devCipher();
            byte[] envelope = cipher.encrypt(PAYLOAD);
            envelope[0] = 0x02;

            assertThatThrownBy(() -> cipher.decrypt(envelope))
                    .isInstanceOf(PayloadUnreadableException.class)
                    .hasFieldOrPropertyWithValue("code", PayloadUnreadableException.MALFORMED_ENVELOPE);
        }

        @Test
        void rejectsTruncatedAndEmptyEnvelopes() {
            AuditPayloadCipher cipher = devCipher();
            byte[] envelope = cipher.encrypt(PAYLOAD);

            assertThatThrownBy(() -> cipher.decrypt(new byte[0])).isInstanceOf(PayloadUnreadableException.class);
            assertThatThrownBy(() -> cipher.decrypt(java.util.Arrays.copyOf(envelope, 8)))
                    .isInstanceOf(PayloadUnreadableException.class)
                    .hasFieldOrPropertyWithValue("code", PayloadUnreadableException.MALFORMED_ENVELOPE);
        }

        @Test
        void rejectsAZeroLengthKeyId() {
            AuditPayloadCipher cipher = devCipher();
            byte[] envelope = cipher.encrypt(PAYLOAD);
            envelope[1] = 0;

            assertThatThrownBy(() -> cipher.decrypt(envelope))
                    .isInstanceOf(PayloadUnreadableException.class)
                    .hasFieldOrPropertyWithValue("code", PayloadUnreadableException.MALFORMED_ENVELOPE);
        }
    }

    // ── Rotation ────────────────────────────────────────────────────────────────────

    @Nested
    class Rotation {

        /**
         * The 400-day retention window (ADR-0050 §7) outlives any sane key lifetime, so rotation must
         * not orphan existing rows. This is why the envelope carries a key id at all.
         */
        @Test
        void aRotatedKeyStillReadsPreRotationPayloads() {
            byte[] sealedWithOldKey = cipher(KEY_A, "k1", "", "test").encrypt(PAYLOAD);

            AuditPayloadCipher rotated = cipher(KEY_B, "k2", "k1:" + KEY_A, "test");

            assertThat(rotated.activeKeyId()).isEqualTo("k2");
            assertThat(rotated.readableKeyIds()).containsExactlyInAnyOrder("k1", "k2");
            assertThat(rotated.decrypt(sealedWithOldKey)).isEqualTo(PAYLOAD);
            // And new writes use the new key.
            assertThat(rotated.encrypt(PAYLOAD)[2 + 1]).isEqualTo((byte) '2');
        }

        @Test
        void anUnconfiguredKeyIdIsReportedDistinctlyFromTampering() {
            byte[] sealed = cipher(KEY_A, "k9", "", "test").encrypt(PAYLOAD);

            assertThatThrownBy(() -> cipher(KEY_B, "k1", "", "test").decrypt(sealed))
                    .isInstanceOf(PayloadUnreadableException.class)
                    .hasFieldOrPropertyWithValue("code", PayloadUnreadableException.UNKNOWN_KEY_ID)
                    .hasMessageContaining("previous-keys");
        }

        /**
         * A key id containing a colon is refused structurally, naming the id as the problem.
         *
         * <p>Without this the first colon is taken as the separator, the remainder fails base-64 decoding,
         * and the operator is told the KEY is malformed when the real fault is its ID — during a
         * fail-closed startup, which is the worst moment for a message that points at the wrong thing.
         */
        @Test
        void aKeyIdContainingAColonIsRefusedAsAnIdProblemNotABase64Problem() {
            assertThatThrownBy(() -> cipher(KEY_A, "k1", "k9:2024:" + KEY_B, "test"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("key ids must not")
                    .hasMessageContaining("':'")
                    // The old failure mode: the remainder was handed to the base-64 parser, which reported
                    // the KEY as invalid. The message must not say that, and must never echo key material.
                    .hasMessageNotContaining("not valid base64")
                    .hasMessageNotContaining(KEY_B);
        }
    }

    // ── Key policy ──────────────────────────────────────────────────────────────────

    @Nested
    class KeyPolicy {

        /**
         * THE case that bit. The predicate used to enumerate where a key was <em>required</em>
         * ({@code prod|indus|alpha}), so an empty profile set took the ephemeral branch — and an empty
         * profile set is exactly what {@code docker-compose.yml} produces for pos-supplier, as it does for
         * almost every service. A deployment against real PostgreSQL would mint a random key per JVM, seal
         * weeks of commercial exchanges with it, log one WARN, and make every payload permanently unreadable
         * on the next restart, reported afterwards as possible tampering.
         *
         * <p>Listed first and separately from the named profiles because a fail-closed control must hold for
         * the environments nobody enumerated, and this is the one nobody did.
         */
        @Test
        void failsStartupWithoutAKeyWhenNoProfileIsActive() {
            assertThatThrownBy(() -> cipher("", "k1", ""))
                    .as("no active profile is the shipped compose shape and MUST require a key: a fail-closed"
                            + " control cannot depend on a profile having been set correctly")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SUPPLIER_AUDIT_ENC_KEY");
        }

        @Test
        void failsStartupWithoutAKeyForAnyProfileThatIsNotDevOrTest() {
            for (String profile : new String[] {"prod", "indus", "alpha", "docker", "staging", "qa", "typo-dev"}) {
                assertThatThrownBy(() -> cipher("", "k1", "", profile))
                        .as(
                                "profile %s must fail closed: the policy is an allowlist of where a key is"
                                        + " OPTIONAL, so an unrecognised or misspelled profile is required, not exempt",
                                profile)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("SUPPLIER_AUDIT_ENC_KEY");
            }
        }

        @Test
        void generatesAnEphemeralKeyInDevAndTest() {
            for (String profile : new String[] {"dev", "test"}) {
                AuditPayloadCipher ephemeral = cipher("", "k1", "", profile);
                assertThat(ephemeral.decrypt(ephemeral.encrypt(PAYLOAD))).isEqualTo(PAYLOAD);
            }
        }

        /**
         * The hole the first inversion left, and the reason presence-based relaxation was reversed.
         *
         * <p>{@code SPRING_PROFILES_ACTIVE=prod,dev} is not hypothetical — nor is a deployment profile that
         * sets {@code spring.profiles.include=dev} to pick up developer conveniences. Under "no active profile
         * is optional" both produced a PRODUCTION JVM with an ephemeral key: the exact outcome the inversion
         * existed to prevent, reached by a different route.
         */
        @Test
        void failsStartupWhenADeploymentProfileIsCombinedWithAnOptionalOne() {
            for (String[] profiles :
                    new String[][] {{"prod", "dev"}, {"dev", "prod"}, {"alpha", "test"}, {"dev", "docker"}}) {
                assertThatThrownBy(() -> cipher("", "k1", "", profiles))
                        .as(
                                "%s must fail closed: a deployment profile always wins over an optional one",
                                java.util.Arrays.toString(profiles))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("SUPPLIER_AUDIT_ENC_KEY");
            }
        }

        @Test
        void relaxesOnlyWhenEVERYActiveProfileIsOptional() {
            // dev, test, or both -- and nothing else. An unrecognised profile alongside them means a key is
            // required, deliberately: a developer running 'dev,local' either drops the extra profile or
            // provides a key, which is the right side to err on for the only control standing between a real
            // deployment and permanently unreadable commercial history.
            for (String[] profiles : new String[][] {{"dev"}, {"test"}, {"dev", "test"}}) {
                AuditPayloadCipher ephemeral = cipher("", "k1", "", profiles);
                assertThat(ephemeral.decrypt(ephemeral.encrypt(PAYLOAD))).isEqualTo(PAYLOAD);
            }
            assertThatThrownBy(() -> cipher("", "k1", "", "dev", "local"))
                    .as("an unrecognised profile alongside dev requires a key -- this is a REVERSAL of the"
                            + " earlier presence-based behaviour, made deliberately")
                    .isInstanceOf(IllegalStateException.class);
        }

        /**
         * The deployment artifacts must carry the variable too, or the fail-closed guard turns into a
         * fail-to-start for every operator who follows the documented compose flow without knowing why.
         *
         * <p>Pinned because this is a three-file agreement — the failure message, the yaml binding, and the
         * deployment env — and nothing in the type system connects any two of them. The gap this closes is the
         * one where the guard is correct, the binding is correct, and the container still cannot start because
         * nobody passed the variable through.
         */
        @Test
        void theDeploymentArtifactsPassTheKeyThrough() throws Exception {
            String compose = java.nio.file.Files.readString(java.nio.file.Path.of("../docker-compose.yml"));
            String envExample = java.nio.file.Files.readString(java.nio.file.Path.of("../.env.example"));

            assertThat(compose)
                    .as("docker-compose.yml must pass SUPPLIER_AUDIT_ENC_KEY into pos-supplier, or the service"
                            + " cannot start -- which is correct behaviour but needs to be reachable")
                    .contains("SUPPLIER_AUDIT_ENC_KEY: ${SUPPLIER_AUDIT_ENC_KEY}");
            assertThat(envExample)
                    .as(".env.example must document it, including that it is REQUIRED, so the first person to"
                            + " hit the startup failure finds the answer where they look first")
                    .contains("SUPPLIER_AUDIT_ENC_KEY=");
        }

        @Test
        void theFailureMessageNamesTheEnvironmentVariableThatIsActuallyBound() throws Exception {
            // The message tells an operator to provision SUPPLIER_AUDIT_ENC_KEY. That is only true because
            // application.yml binds it; relaxed binding alone would have resolved
            // POS_SUPPLIER_AUDIT_ENCRYPTION_KEY instead, so an operator following the message would have set
            // a variable nothing read and started with an ephemeral key believing otherwise.
            String applicationYml =
                    java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/application.yml"));

            assertThat(applicationYml)
                    .as("the property must be bound from the env var the failure message names, or the"
                            + " instruction is wrong in the one place it is read")
                    .contains("key: ${SUPPLIER_AUDIT_ENC_KEY:}");
            assertThatThrownBy(() -> cipher("", "k1", "")).hasMessageContaining("SUPPLIER_AUDIT_ENC_KEY");
        }

        @Test
        void aRealKeyIsAcceptedInProd() {
            assertThatCode(() -> cipher(KEY_A, "k1", "", "prod")).doesNotThrowAnyException();
        }

        @Test
        void rejectsAKeyOfTheWrongLengthOrBadBase64() {
            String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

            assertThatThrownBy(() -> cipher(tooShort, "k1", "", "test"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("32 bytes");
            assertThatThrownBy(() -> cipher("not base64 !!", "k1", "", "test"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("base64");
        }

        @Test
        void keyFailureMessagesNeverEchoKeyMaterial() {
            String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

            assertThatThrownBy(() -> cipher(tooShort, "k1", "", "test")).hasMessageNotContaining(tooShort);
        }

        @Test
        void rejectsABlankKeyIdAndAnOverlongOne() {
            assertThatThrownBy(() -> cipher(KEY_A, "  ", "", "test"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("key-id");
            assertThatThrownBy(() -> cipher(KEY_A, "k".repeat(256), "", "test"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("255 bytes");
        }

        @Test
        void rejectsMalformedPreviousKeyEntries() {
            assertThatThrownBy(() -> cipher(KEY_A, "k1", "no-separator", "test"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("keyId:base64");
        }
    }
}
