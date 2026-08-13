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

    /**
     * Per-binding, classification-driven redaction (issue #1259, ADR-0050 §7 minimization). These are the
     * §7 examples verbatim: a customer identifier in a fleet workorder authorization payload, and pricing
     * in a PRICAT-class document.
     */
    @Nested
    class ClassificationDrivenRedaction {

        private static final String CUSTOMER_NUMBER = "FLEET-CUST-0042";
        private static final String NET_PRICE = "184.60";

        private static final String WORKORDER_XML = "<WorkorderAuthorization><CustomerNumber>" + CUSTOMER_NUMBER
                + "</CustomerNumber><Vin>WVWZZZ1JZXW000001</Vin><Article>225/45R17</Article>"
                + "</WorkorderAuthorization>";

        private static final String PRICAT_JSON =
                "{\"article\":\"225/45R17\",\"netPrice\":\"" + NET_PRICE + "\",\"net_price\":184.60}";

        @Test
        void withoutAClassificationTheCustomerIdentifierSurvivesRedaction() {
            // The gap #1259 records: name-based credential redaction alone keeps every
            // non-credential-named field of a REDACTED capture, for 400 days.
            assertThat(PayloadRedactor.redact(WORKORDER_XML)).contains(CUSTOMER_NUMBER);
            assertThat(PayloadRedactor.redact(WORKORDER_XML, java.util.Set.of()))
                    .contains(CUSTOMER_NUMBER);
        }

        @Test
        void customerIdentifierClassificationRedactsFleetWorkorderIdentity() {
            String redacted = PayloadRedactor.redact(
                    WORKORDER_XML,
                    java.util.Set.of(
                            com.positivity.supplier.internal.enums.RedactionClassification.CUSTOMER_IDENTIFIER));

            assertThat(redacted).doesNotContain(CUSTOMER_NUMBER).doesNotContain("WVWZZZ1JZXW000001");
            assertThat(redacted)
                    .as("the commercial content the classification does not cover is the reason payloads are kept")
                    .contains("225/45R17");
            assertThat(redacted)
                    .as("value-preserving in shape, like credential redaction")
                    .contains("<CustomerNumber>")
                    .contains("</CustomerNumber>");
        }

        @Test
        void commercialPricingClassificationRedactsPricatFields() {
            String redacted = PayloadRedactor.redact(
                    PRICAT_JSON,
                    java.util.Set.of(
                            com.positivity.supplier.internal.enums.RedactionClassification.COMMERCIAL_PRICING));

            assertThat(redacted).doesNotContain(NET_PRICE).contains("225/45R17");
        }

        @Test
        void classificationsAreIndependentOfEachOther() {
            // Declaring pricing must not start redacting customer identity, or a binding's declared
            // scope and its actual scope diverge silently.
            String redacted = PayloadRedactor.redact(
                    WORKORDER_XML,
                    java.util.Set.of(
                            com.positivity.supplier.internal.enums.RedactionClassification.COMMERCIAL_PRICING));

            assertThat(redacted).contains(CUSTOMER_NUMBER);
        }

        @Test
        void credentialRedactionAlwaysAppliesWhateverTheClassifications() {
            String document = "<Header><Password>" + SECRET + "</Password></Header>";

            assertThat(PayloadRedactor.redact(document, java.util.Set.of())).doesNotContain(SECRET);
            assertThat(PayloadRedactor.redact(
                            document,
                            java.util.Set.of(
                                    com.positivity.supplier.internal.enums.RedactionClassification
                                            .CUSTOMER_IDENTIFIER)))
                    .doesNotContain(SECRET);
        }

        @Test
        void everyClassificationHasACompiledVocabulary() {
            // A classification that redacts nothing must not exist: PayloadRedactor fails class
            // initialization on a constant with no vocabulary, and this test makes that a build failure
            // by exercising every constant end to end.
            for (var classification : com.positivity.supplier.internal.enums.RedactionClassification.values()) {
                assertThat(PayloadRedactor.redact("<x/>", java.util.Set.of(classification)))
                        .isEqualTo("<x/>");
            }
        }

        @Test
        void classificationsApplyAtRedactedAndNeverWidenFullOrMetadataOnly() {
            var classes = java.util.Set.of(
                    com.positivity.supplier.internal.enums.RedactionClassification.CUSTOMER_IDENTIFIER);

            assertThat(PayloadRedactor.applyCaptureLevel(WORKORDER_XML, PayloadCaptureLevel.REDACTED, classes))
                    .doesNotContain(CUSTOMER_NUMBER);
            assertThat(PayloadRedactor.applyCaptureLevel(WORKORDER_XML, PayloadCaptureLevel.FULL, classes))
                    .as("FULL is an explicit operator choice; classifications narrow REDACTED, they do not"
                            + " create a fourth level")
                    .isEqualTo(WORKORDER_XML);
            assertThat(PayloadRedactor.applyCaptureLevel(WORKORDER_XML, PayloadCaptureLevel.METADATA_ONLY, classes))
                    .isNull();
        }

        @Test
        void positionalDocumentsAreUntouchedByClassifications() {
            // The honestly-recorded limit (unchanged by #1259): an EDIFACT UNB segment carries the
            // customer reference and password POSITIONALLY, so name-based classification redaction finds
            // nothing and METADATA_ONLY remains the only safe level for positional families.
            String edifact = "UNB+UNOC:3+SENDER+RECIPIENT+260813:1030+" + CUSTOMER_NUMBER + "'";

            assertThat(PayloadRedactor.redact(
                            edifact,
                            java.util.Set.of(
                                    com.positivity.supplier.internal.enums.RedactionClassification
                                            .CUSTOMER_IDENTIFIER)))
                    .isEqualTo(edifact);
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

        /**
         * The conventions that matter in a URL and are too ambiguous to redact in a body. A URI-only name set
         * exists precisely so that widening these does not start destroying {@code <Key>} elements inside
         * vendor documents at {@code REDACTED}.
         */
        @ParameterizedTest(name = "query parameter {0} is redacted")
        @ValueSource(
                strings = {
                    "key",
                    "token",
                    "api-key",
                    "sig",
                    "signature",
                    "subscription-key",
                    "auth",
                    "code",
                    "access-token",
                    "x-amz-signature",
                    "awsAccessKeyId"
                })
        void redactsTheUriOnlySensitiveParameterNames(String parameter) {
            String uri = PayloadRedactor.redactUri(
                    "https://edi.example/stock?" + parameter + "=SeCretValue123&article=205", PayloadCaptureLevel.FULL);

            assertThat(uri).doesNotContain("SeCretValue123");
            assertThat(uri).contains("article=205");
        }

        @Test
        void doesNotRedactThoseNamesInsideABodyAtRedactedLevel() {
            // The other half of the URI-only decision: a vendor document's <Key> is commercial content, and
            // redacting it would silently destroy data in a store that keeps it for 400 days.
            String body = PayloadRedactor.applyCaptureLevel(
                    "<Item><Key>ART-205-45-17</Key><Token>SEQ-99</Token></Item>", PayloadCaptureLevel.REDACTED);

            assertThat(body)
                    .as("URI parameter names must NOT leak into body redaction -- that is why the sets are"
                            + " separate")
                    .contains("ART-205-45-17")
                    .contains("SEQ-99");
        }

        @Test
        void stripsUserinfoAtEveryCaptureLevelIncludingMetadataOnly() {
            for (PayloadCaptureLevel level : PayloadCaptureLevel.values()) {
                String uri = PayloadRedactor.redactUri("https://apiuser:hunter2@edi.example/a25/stock", level);

                assertThat(uri)
                        .as(
                                "userinfo is a plaintext credential (ADR-0050 §4) and sits OUTSIDE the query"
                                        + " string, so dropping the query at %s would not remove it",
                                level)
                        .doesNotContain("hunter2");
                assertThat(uri).contains("edi.example/a25/stock");
            }
        }

        @Test
        void redactsCredentialsInAUrlEmbeddedInFreeText() {
            String detail = PayloadRedactor.redactEmbeddedUris(
                    "Vendor redirected to https://cdn.example/doc?sig=AbC123&exp=99 (302); retry advised");

            assertThat(detail).doesNotContain("AbC123");
            assertThat(detail)
                    .as("the operator-facing diagnostic must survive the redaction that protects it")
                    .contains("Vendor redirected to")
                    .contains("(302); retry advised");
        }

        @Test
        void leavesFreeTextWithNoUrlUntouched() {
            String detail = "Read timed out after 30000 ms";

            assertThat(PayloadRedactor.redactEmbeddedUris(detail)).isEqualTo(detail);
            assertThat(PayloadRedactor.redactEmbeddedUris(null)).isNull();
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
