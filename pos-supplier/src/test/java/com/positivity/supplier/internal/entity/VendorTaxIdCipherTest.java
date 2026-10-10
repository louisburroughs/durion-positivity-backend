package com.positivity.supplier.internal.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.exception.VendorTaxIdUnreadableException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.mock.env.MockEnvironment;

/**
 * The vendor tax-registration number cipher (#2621; Security ruling on #2617, ruling 3): the shared envelope,
 * the row-bound AAD, the fail-closed key policy, its own key, and rotation. Numbers are obviously fake.
 *
 * <p>{@code @Isolated}: the ephemeral-key WARN is captured on logback's shared loggers, and a Spring context
 * starting in a concurrent class resets them, which would leave the capture empty.
 */
@Isolated
@DisplayName("VendorTaxIdCipher (#2621)")
class VendorTaxIdCipherTest {

    private static final String KEY_A = Base64.getEncoder().encodeToString(fill((byte) 0x31));
    private static final String KEY_B = Base64.getEncoder().encodeToString(fill((byte) 0x42));
    private static final String NUMBER = "000-00-1234";
    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-00000000000a");
    private static final UUID OTHER_TENANT = UUID.fromString("01900000-0000-7000-8000-00000000000b");
    private static final UUID VENDOR = UUID.fromString("01980000-0000-7000-8000-000000000a01");
    private static final UUID OTHER_VENDOR = UUID.fromString("01980000-0000-7000-8000-000000000a02");
    private static final UUID REGISTRATION = UUID.fromString("01980000-0000-7000-8000-000000000b01");
    private static final UUID OTHER_REGISTRATION = UUID.fromString("01980000-0000-7000-8000-000000000b02");

    private static byte[] fill(byte value) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, value);
        return key;
    }

    private static VendorTaxIdCipher cipher(String key, String keyId, String previous, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new VendorTaxIdCipher(environment, key, keyId, previous);
    }

    private static VendorTaxIdCipher testCipher() {
        return cipher(KEY_A, "k1", "", "test");
    }

    @Test
    @DisplayName("round-trips for the same tenant, vendor and registration; the ciphertext holds no number")
    void roundTrips() {
        VendorTaxIdCipher cipher = testCipher();
        String sealed = cipher.seal(TENANT, VENDOR, REGISTRATION, NUMBER);

        assertThat(cipher.open(TENANT, VENDOR, REGISTRATION, sealed)).isEqualTo(NUMBER);
        String decoded = new String(Base64.getDecoder().decode(sealed), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(decoded.contains(NUMBER) || decoded.contains("000001234"))
                .as("number absent from the envelope")
                .isFalse();
    }

    @Test
    @DisplayName("the envelope is the shared one: version 0x01, then the key id")
    void sharesTheAuditEnvelope() {
        byte[] envelope = Base64.getDecoder().decode(testCipher().seal(TENANT, VENDOR, REGISTRATION, NUMBER));

        assertThat(envelope[0]).isEqualTo(AesGcmEnvelopeCipher.ENVELOPE_VERSION);
        assertThat(envelope[1]).isEqualTo((byte) 2);
        assertThat(new String(envelope, 2, 2, java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("k1");
    }

    @Test
    @DisplayName("nonces never repeat for one key and one number")
    void neverReusesANonce() {
        VendorTaxIdCipher cipher = testCipher();
        Set<String> sealed = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            sealed.add(cipher.seal(TENANT, VENDOR, REGISTRATION, NUMBER));
        }
        assertThat(sealed).hasSize(200);
    }

    /** AC 4 at the cipher: the AAD binds tenant, vendor and registration. */
    @Nested
    @DisplayName("AC 4: a ciphertext opens only for the row it was sealed for")
    class AadBinding {

        @Test
        @DisplayName("another vendor, registration or tenant fails authentication and reveals nothing")
        void otherRowsFailAuthentication() {
            VendorTaxIdCipher cipher = testCipher();
            String sealed = cipher.seal(TENANT, VENDOR, REGISTRATION, NUMBER);

            for (UUID[] row : new UUID[][] {
                {TENANT, OTHER_VENDOR, REGISTRATION},
                {TENANT, VENDOR, OTHER_REGISTRATION},
                {OTHER_TENANT, VENDOR, REGISTRATION}
            }) {
                assertThatThrownBy(() -> cipher.open(row[0], row[1], row[2], sealed))
                        .isInstanceOfSatisfying(VendorTaxIdUnreadableException.class, failure -> {
                            assertThat(failure.getCode()).isEqualTo("SUPPLIER_VENDOR_TAX_ID_UNREADABLE");
                            assertThat(failure.getFailure()).isEqualTo("AUTHENTICATION_FAILED");
                            assertThat(failure.getKeyId()).isEqualTo("k1");
                            assertThat(failure.getMessage()).as("number absent").doesNotContain(NUMBER);
                        });
            }
        }

        @Test
        @DisplayName("garbage is MALFORMED_ENVELOPE, an unknown key id UNKNOWN_KEY_ID")
        void malformedAndUnknownKey() {
            assertThatThrownBy(() -> testCipher().open(TENANT, VENDOR, REGISTRATION, "not base64 !!"))
                    .isInstanceOfSatisfying(
                            VendorTaxIdUnreadableException.class,
                            failure -> assertThat(failure.getFailure()).isEqualTo("MALFORMED_ENVELOPE"));
            String sealedUnderK9 = cipher(KEY_B, "k9", "", "test").seal(TENANT, VENDOR, REGISTRATION, NUMBER);
            assertThatThrownBy(() -> testCipher().open(TENANT, VENDOR, REGISTRATION, sealedUnderK9))
                    .isInstanceOfSatisfying(VendorTaxIdUnreadableException.class, failure -> {
                        assertThat(failure.getFailure()).isEqualTo("UNKNOWN_KEY_ID");
                        assertThat(failure.getMessage())
                                .contains("pos.supplier.vendor-tax-id.encryption.previous-keys");
                    });
        }
    }

    @Test
    @DisplayName("rotation: a number sealed under a retired key opens through previous-keys")
    void rotationReadsThroughPreviousKeys() {
        String sealedUnderOld = cipher(KEY_A, "k1", "", "test").seal(TENANT, VENDOR, REGISTRATION, NUMBER);
        VendorTaxIdCipher rotated = cipher(KEY_B, "k2", "k1:" + KEY_A, "prod");

        assertThat(rotated.open(TENANT, VENDOR, REGISTRATION, sealedUnderOld)).isEqualTo(NUMBER);
        assertThat(rotated.activeKeyId()).isEqualTo("k2");
    }

    /** AC 5. */
    @Nested
    @DisplayName("AC 5: key policy — fail closed, its own key")
    class KeyPolicy {

        @Test
        @DisplayName(
                "no key with {}, {alpha}, {prod,dev} or {unknown}: startup fails naming SUPPLIER_VENDOR_TAXID_ENC_KEY")
        void failsClosed() {
            for (String[] profiles : new String[][] {{}, {"alpha"}, {"prod", "dev"}, {"unknown"}}) {
                assertThatThrownBy(() -> cipher("", "k1", "", profiles))
                        .as(java.util.Arrays.toString(profiles))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("SUPPLIER_VENDOR_TAXID_ENC_KEY")
                        .hasMessageNotContaining("SUPPLIER_AUDIT_ENC_KEY");
            }
        }

        @Test
        @DisplayName("{dev} or {test} starts with an ephemeral key and exactly one WARN")
        void ephemeralInDevAndTest() {
            ch.qos.logback.classic.Logger logger =
                    (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AesGcmEnvelopeCipher.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                cipher("", "k1", "", "dev");
                assertThat(appender.list)
                        .filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                        .singleElement()
                        .satisfies(
                                event -> assertThat(event.getFormattedMessage()).contains("EPHEMERAL"));
            } finally {
                logger.detachAppender(appender);
            }
            for (String profile : new String[] {"dev", "test"}) {
                VendorTaxIdCipher ephemeral = cipher("", "k1", "", profile);
                assertThat(ephemeral.open(
                                TENANT, VENDOR, REGISTRATION, ephemeral.seal(TENANT, VENDOR, REGISTRATION, NUMBER)))
                        .isEqualTo(NUMBER);
            }
        }

        @Test
        @DisplayName("the exchange-audit key is never read: with only that key set, startup still fails")
        void neverReadsTheAuditKey() {
            MockEnvironment environment = new MockEnvironment()
                    .withProperty("pos.supplier.audit.encryption.key", KEY_A)
                    .withProperty("SUPPLIER_AUDIT_ENC_KEY", KEY_A);
            environment.setActiveProfiles("prod");

            assertThatThrownBy(() -> new VendorTaxIdCipher(environment, "", "k1", ""))
                    .hasMessageContaining("SUPPLIER_VENDOR_TAXID_ENC_KEY");
        }

        @Test
        @DisplayName("key failures never echo key material; a real key is accepted in prod")
        void keyMaterialIsNeverEchoed() {
            String tooShort = Base64.getEncoder().encodeToString(new byte[16]);
            assertThatThrownBy(() -> cipher(tooShort, "k1", "", "prod"))
                    .hasMessageContaining("32 bytes")
                    .hasMessageNotContaining(tooShort);
            assertThatCode(() -> cipher(KEY_A, "k1", "", "prod")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("application.yml binds the property from SUPPLIER_VENDOR_TAXID_ENC_KEY, a separate variable")
        void yamlBindsTheNamedVariable() throws Exception {
            String yaml = Files.readString(Path.of("src/main/resources/application.yml"));

            assertThat(yaml).contains("vendor-tax-id:").contains("key: ${SUPPLIER_VENDOR_TAXID_ENC_KEY:}");
        }

        @Test
        @DisplayName("the deployment artifacts pass the key through, with a placeholder only")
        void deploymentArtifactsPassTheKeyThrough() throws Exception {
            assertThat(Files.readString(Path.of("../docker-compose.yml")))
                    .contains("SUPPLIER_VENDOR_TAXID_ENC_KEY: ${SUPPLIER_VENDOR_TAXID_ENC_KEY}");
            assertThat(Files.readString(Path.of("../.env.example"))).contains("SUPPLIER_VENDOR_TAXID_ENC_KEY=\n");
            assertThat(Files.readString(Path.of("../deployment/alpha/deploy-backend.sh")))
                    .contains("require_supplier_key SUPPLIER_VENDOR_TAXID_ENC_KEY");
        }
    }
}
