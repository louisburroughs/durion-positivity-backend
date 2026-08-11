package com.positivity.supplier.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.supplier.internal.enums.PayloadCaptureLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Credential redaction for exchange-audit payloads (ADR-0050 §7).
 *
 * <p>These are the tests that decide whether the audit store becomes a credential database. The base
 * client hands over raw wire documents, so if redaction misses a field the plaintext is written into a
 * 400-day retention window and cannot be un-written. Every assertion below is therefore "the secret is
 * absent", not merely "the marker is present".
 */
class PayloadRedactorTest {

    private static final String SECRET = "hunter2-actual-password";

    @Nested
    class XmlDocuments {

        /** The EDIWheel A2.5 shape: credentials repeated inside the message header element. */
        @Test
        void redactsEdiwheelStyleCredentialElements() {
            String document = "<StockInquiry><Header><UserID>michelin-user</UserID>"
                    + "<Password>" + SECRET + "</Password><ApiKey>key-abc-123</ApiKey></Header>"
                    + "<Article>225/45R17</Article></StockInquiry>";

            String redacted = PayloadRedactor.redact(document);

            assertThat(redacted)
                    .doesNotContain(SECRET)
                    .doesNotContain("key-abc-123")
                    .doesNotContain("michelin-user");
            assertThat(redacted)
                    .as("the business content is the reason we keep payloads at all")
                    .contains("225/45R17");
            assertThat(redacted)
                    .as("keeping the element shows an operator the field WAS present, which is often the diagnostic")
                    .contains("<Password>")
                    .contains("</Password>");
        }

        @Test
        void redactsNamespacedElements() {
            String document = "<soap:Body><ns1:Password>" + SECRET + "</ns1:Password></soap:Body>";

            assertThat(PayloadRedactor.redact(document)).doesNotContain(SECRET);
        }

        @Test
        void redactsElementsCarryingAttributes() {
            String document = "<Password type=\"plain\" enc=\"none\">" + SECRET + "</Password>";

            assertThat(PayloadRedactor.redact(document)).doesNotContain(SECRET);
        }

        @Test
        void redactsMultilineElementValues() {
            String document = "<Password>\n  " + SECRET + "\n</Password>";

            assertThat(PayloadRedactor.redact(document)).doesNotContain(SECRET);
        }

        @Test
        void redactsAttributeStyleCredentials() {
            assertThat(PayloadRedactor.redact("<Login password=\"" + SECRET + "\" user=\"x\"/>"))
                    .doesNotContain(SECRET);
            assertThat(PayloadRedactor.redact("<Login password='" + SECRET + "'/>"))
                    .doesNotContain(SECRET);
        }

        @Test
        void redactsEveryOccurrenceNotJustTheFirst() {
            String document = "<A><Password>" + SECRET + "</Password></A><B><Password>" + SECRET + "</Password></B>";

            assertThat(PayloadRedactor.redact(document)).doesNotContain(SECRET);
        }

        @Test
        void isCaseInsensitiveBecauseVendorsAreInconsistent() {
            assertThat(PayloadRedactor.redact("<PASSWORD>" + SECRET + "</PASSWORD>"))
                    .doesNotContain(SECRET);
            assertThat(PayloadRedactor.redact("<passWord>" + SECRET + "</passWord>"))
                    .doesNotContain(SECRET);
        }
    }

    @Nested
    class JsonAndFormBodies {

        @Test
        void redactsJsonCredentialFields() {
            String body = "{\"userId\":\"u1\",\"password\":\"" + SECRET + "\",\"sku\":\"225/45R17\"}";

            String redacted = PayloadRedactor.redact(body);

            assertThat(redacted).doesNotContain(SECRET);
            assertThat(redacted).contains("225/45R17");
        }

        @Test
        void redactsAnOauthTokenResponse() {
            String body = "{\"access_token\":\"eyJhbGciOi.SECRETPART.sig\",\"expires_in\":3600}";

            String redacted = PayloadRedactor.redact(body);

            assertThat(redacted).doesNotContain("SECRETPART").doesNotContain("eyJhbGciOi");
            assertThat(redacted).as("non-secret metadata stays useful").contains("3600");
        }

        @Test
        void redactsAnOauthFormRequest() {
            String body = "grant_type=client_credentials&client_secret=" + SECRET + "&scope=orders";

            String redacted = PayloadRedactor.redact(body);

            assertThat(redacted).doesNotContain(SECRET);
            assertThat(redacted).contains("grant_type=client_credentials").contains("scope=orders");
        }

        @Test
        void redactsJsonValuesContainingEscapedQuotes() {
            String body = "{\"password\":\"he said \\\"" + SECRET + "\\\" loudly\"}";

            assertThat(PayloadRedactor.redact(body)).doesNotContain(SECRET);
        }
    }

    @Nested
    class Robustness {

        @Test
        void nullAndEmptyPassThroughUnchanged() {
            assertThat(PayloadRedactor.redact(null)).isNull();
            assertThat(PayloadRedactor.redact("")).isEmpty();
        }

        @Test
        void aDocumentWithNoCredentialsIsUnchanged() {
            String document = "<StockInquiry><Article>225/45R17</Article></StockInquiry>";

            assertThat(PayloadRedactor.redact(document)).isEqualTo(document);
        }

        /**
         * A '$' or backslash in a credential value must not be interpreted as a regex replacement
         * reference. Without {@code Matcher.quoteReplacement} this throws or corrupts the output --
         * and a redactor that throws means the observer's catch swallows the row entirely.
         */
        @ParameterizedTest
        @ValueSource(strings = {"pa$$word", "back\\slash", "$1$2$3", "dollar$end", "brace{}", "caret^tilde~"})
        void survivesRegexMetacharactersInTheSecretValue(String awkwardSecret) {
            String document = "<Password>" + awkwardSecret + "</Password>";

            String redacted = PayloadRedactor.redact(document);

            assertThat(redacted).doesNotContain(awkwardSecret);
            assertThat(redacted).contains(PayloadRedactor.REDACTED);
        }

        @Test
        void handlesAMixedFormatBodyBecauseContentTypesAreUnreliable() {
            String body = "<Password>" + SECRET + "</Password>\n{\"client_secret\":\"" + SECRET + "\"}";

            assertThat(PayloadRedactor.redact(body)).doesNotContain(SECRET);
        }
    }

    @Nested
    class CaptureLevels {

        private static final String DOCUMENT = "<Password>" + SECRET + "</Password><Article>X</Article>";

        @Test
        void metadataOnlyKeepsNoPayloadAtAll() {
            assertThat(PayloadRedactor.applyCaptureLevel(DOCUMENT, PayloadCaptureLevel.METADATA_ONLY))
                    .isNull();
        }

        @Test
        void redactedKeepsTheDocumentWithoutTheCredential() {
            String result = PayloadRedactor.applyCaptureLevel(DOCUMENT, PayloadCaptureLevel.REDACTED);

            assertThat(result).doesNotContain(SECRET).contains("<Article>X</Article>");
        }

        @Test
        void fullKeepsTheDocumentAsSent() {
            // FULL is an explicit operator choice for this binding; it preserves body content.
            assertThat(PayloadRedactor.applyCaptureLevel(DOCUMENT, PayloadCaptureLevel.FULL))
                    .isEqualTo(DOCUMENT);
        }

        @Test
        void fullIsTheOnlyCaptureLevelThatMayRetainACredential() {
            // The real invariant. A new level added without a redaction decision must fail here
            // rather than silently defaulting to keeping the document verbatim.
            for (PayloadCaptureLevel level : PayloadCaptureLevel.values()) {
                String result = PayloadRedactor.applyCaptureLevel(DOCUMENT, level);
                if (level == PayloadCaptureLevel.FULL) {
                    assertThat(result).isEqualTo(DOCUMENT);
                } else {
                    assertThat(result == null || !result.contains(SECRET))
                            .as("capture level %s must not retain the credential", level)
                            .isTrue();
                }
            }
        }
    }

    // ── URI redaction (ADR-0050 §4/§7) ──────────────────────────────────────────────

    @Nested
    @DisplayName("request URIs are redacted before permanent storage")
    class UriRedaction {

        /**
         * The reason this matters more than for any payload: {@code endpoint_uri} is stored in plaintext at
         * EVERY capture level and is exempt from the 400-day purge, so anything left in it is kept forever and
         * is readable through the metadata listing.
         */
        @Test
        void redactsCredentialQueryParametersAtFullCapture() {
            String uri = PayloadRedactor.redactUri(
                    "https://edi.example/stock?apikey=live-secret-value&article=225%2F45R17", PayloadCaptureLevel.FULL);

            assertThat(uri).doesNotContain("live-secret-value").contains(PayloadRedactor.REDACTED);
            assertThat(uri)
                    .as("non-sensitive parameters survive at FULL -- they are the commercial content this"
                            + " level exists to retain")
                    .contains("article=225%2F45R17");
        }

        @Test
        void keepsThePathIntactSoTheTrailStillSaysWhichEndpointWasCalled() {
            assertThat(PayloadRedactor.redactUri("https://edi.example/a25/stock/inquiry", PayloadCaptureLevel.FULL))
                    .isEqualTo("https://edi.example/a25/stock/inquiry");
        }

        @Test
        void dropsTheWholeQueryStringAtMetadataOnly() {
            String uri = PayloadRedactor.redactUri(
                    "https://edi.example/stock?orderNumber=SO-99123&account=0000012345&from=2026-08-01",
                    PayloadCaptureLevel.METADATA_ONLY);

            assertThat(uri)
                    .as("METADATA_ONLY promises to retain NO content, and order numbers, account references"
                            + " and date ranges are content -- redacting only the sensitive-name list would"
                            + " leave commercial data in the one column that is never purged")
                    .isEqualTo("https://edi.example/stock");
        }

        @Test
        void redactsButKeepsTheQueryStringAtRedacted() {
            String uri = PayloadRedactor.redactUri(
                    "https://edi.example/stock?password=hunter2&article=205", PayloadCaptureLevel.REDACTED);

            assertThat(uri).doesNotContain("hunter2").contains("article=205");
        }

        @Test
        void nullInNullOut() {
            assertThat(PayloadRedactor.redactUri(null, PayloadCaptureLevel.FULL))
                    .isNull();
            assertThat(PayloadRedactor.redactUri(null, PayloadCaptureLevel.METADATA_ONLY))
                    .isNull();
        }

        @Test
        void aUriWithNoQueryStringIsUnchangedAtEveryLevel() {
            for (PayloadCaptureLevel level : PayloadCaptureLevel.values()) {
                assertThat(PayloadRedactor.redactUri("https://edi.example/a25/stock", level))
                        .as("level %s must not mangle a query-less URI", level)
                        .isEqualTo("https://edi.example/a25/stock");
            }
        }
    }
}
