package com.positivity.supplier.internal.adapter.ediwheelb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierPriceCatalogEntry;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("EDIWheel PRICAT B4.0 codec (#1224)")
class EdiwheelB40PricatCodecTest {

    private final EdiwheelB40PricatCodec codec = new EdiwheelB40PricatCodec(new ObjectMapper());

    private static String goldenDocument() throws IOException {
        try (InputStream in =
                EdiwheelB40PricatCodecTest.class.getResourceAsStream("/fixtures/pricat-b40-sample.json")) {
            assertThat(in)
                    .as("golden PRICAT fixture must be on the test classpath")
                    .isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void registersUnderThePriceCatalogTriple() {
        assertThat(codec.capability()).isEqualTo(SupplierCapability.PRICE_CATALOG);
        assertThat(codec.family()).isEqualTo(ProtocolFamily.EDIWHEEL_B);
        assertThat(codec.version()).isEqualTo(ProtocolVersion.B4_0);
    }

    @Nested
    @DisplayName("buildRequest")
    class BuildRequest {

        @Test
        void carriesBuyerIdentityFromTheBillingAccount() {
            SupplierRequestSpec spec = codec.buildRequest(new PartyContext("30012456", "91", null), null);

            assertThat(spec.method()).isEqualTo("GET");
            assertThat(spec.queryParams())
                    .containsEntry("buyerParty", "30012456")
                    .containsEntry("agencyCode", "91");
            assertThat(spec.queryParams()).doesNotContainKey("ean");
            assertThat(spec.accept()).isEqualTo("application/json");
        }

        @Test
        void omitsTheAgencyCodeWhenTheAccountStatesNone() {
            SupplierRequestSpec spec = codec.buildRequest(new PartyContext("30012456", null, null), null);

            assertThat(spec.queryParams()).doesNotContainKey("agencyCode");
        }

        @Test
        void appliesTheOptionalSingleEanFilter() {
            SupplierRequestSpec spec = codec.buildRequest(new PartyContext("30012456", "91", null), " 3528700607031 ");

            assertThat(spec.queryParams()).containsEntry("ean", "3528700607031");
        }

        @Test
        void isRetryableBecauseAPricatFetchHasNoCommercialSideEffect() {
            assertThat(codec.buildRequest(new PartyContext("30012456", "91", null), null)
                            .idempotent())
                    .isTrue();
        }

        @Test
        void neverCarriesACredentialInTheQuery() {
            SupplierRequestSpec spec = codec.buildRequest(new PartyContext("30012456", "91", null), null);

            assertThat(spec.queryParams().keySet()).containsExactly("buyerParty", "agencyCode");
        }

        @Test
        void omitsABlankAgencyCode() {
            // A stated-but-blank agency code is not the same as an absent one to the JSON reader,
            // but it must still be omitted from the query.
            SupplierRequestSpec spec = codec.buildRequest(new PartyContext("30012456", "  ", null), null);

            assertThat(spec.queryParams()).doesNotContainKey("agencyCode");
        }

        @Test
        void omitsABlankEanFilter() {
            SupplierRequestSpec spec = codec.buildRequest(new PartyContext("30012456", "91", null), "   ");

            assertThat(spec.queryParams()).doesNotContainKey("ean");
        }
    }

    @Nested
    @DisplayName("decode")
    class Decode {

        @Test
        void readsHeaderFactsFromTheGoldenDocument() throws IOException {
            PricatDocument document = codec.decode(goldenDocument());

            assertThat(document.documentId()).isEqualTo("PRICAT-4046266_202305120844");
            assertThat(document.documentDate()).isEqualTo(LocalDate.of(2023, 5, 12));
            assertThat(document.countryCode()).isEqualTo("SE");
            assertThat(document.currency()).isEqualTo("SEK");
            assertThat(document.linesFetched()).isEqualTo(4);
        }

        @Test
        void mapsIdentityPricesAndFeesVerbatim() throws IOException {
            SupplierPriceCatalogEntry first =
                    codec.decode(goldenDocument()).entries().getFirst();

            assertThat(first.articleEan()).isEqualTo("3528709999083");
            assertThat(first.supplierArticleCode()).isEqualTo("999908");
            assertThat(first.suggestedRetailPrice()).isEqualByComparingTo(new BigDecimal("3195.00"));
            assertThat(first.grossPrice()).isEqualByComparingTo(new BigDecimal("2895.50"));
            assertThat(first.netPrice()).isEqualByComparingTo(new BigDecimal("2450.25"));
            assertThat(first.taxRate()).isEqualByComparingTo(new BigDecimal("25"));
            assertThat(first.recyclingFee()).isEqualByComparingTo(new BigDecimal("35.00"));
            assertThat(first.countryCode()).isEqualTo("SE");
            assertThat(first.currency()).isEqualTo("SEK");
            assertThat(first.positionNumber()).isEqualTo(1);
        }

        @Test
        void prefersTheNetValidityDateWhenANetPriceIsStated() throws IOException {
            SupplierPriceCatalogEntry first =
                    codec.decode(goldenDocument()).entries().getFirst();

            // Gross says 2021-01-01, net says 2026-02-01; the net price is what a buyer pays.
            assertThat(first.effectiveFrom()).isEqualTo(LocalDate.of(2026, 2, 1));
        }

        @Test
        void fallsBackToTheGrossValidityDateWhenNoNetPriceIsStated() throws IOException {
            SupplierPriceCatalogEntry second =
                    codec.decode(goldenDocument()).entries().get(1);

            assertThat(second.netPrice()).isNull();
            assertThat(second.effectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 15));
        }

        @Test
        void acceptsCommaDecimalsAsSomeMarketsStateThem() throws IOException {
            SupplierPriceCatalogEntry second =
                    codec.decode(goldenDocument()).entries().get(1);

            assertThat(second.grossPrice()).isEqualByComparingTo(new BigDecimal("1450.00"));
        }

        @Test
        void carriesTheCrossReferenceCodeAsASecondIdentity() throws IOException {
            SupplierPriceCatalogEntry second =
                    codec.decode(goldenDocument()).entries().get(1);

            assertThat(second.articleEan()).isNull();
            assertThat(second.xReferenceCode()).isEqualTo("0123456789012");
        }

        @Test
        void rejectsUnusableLinesWithoutDiscardingTheDocument() throws IOException {
            PricatDocument document = codec.decode(goldenDocument());

            assertThat(document.entries()).hasSize(2);
            assertThat(document.rejectedLines()).hasSize(2);
            assertThat(document.rejectedLines())
                    .anySatisfy(line -> assertThat(line.detail()).contains("grossPrice is not a number"));
            assertThat(document.rejectedLines())
                    .anySatisfy(line -> assertThat(line.detail()).contains("product identity"));
            // Every line is accounted for: entries + rejected == what the vendor sent.
            assertThat(document.entries().size() + document.rejectedLines().size())
                    .isEqualTo(document.linesFetched());
        }

        @Test
        void failsTheDocumentWhenTheVendorSignalsAnErrorCode() {
            String body = """
                    {"envelopeHeader":{"documentId":"D1","countryCode":"SE","currency":"SEK","errorCode":"42"},
                     "articles":[]}
                    """;

            assertThatThrownBy(() -> codec.decode(body))
                    .isInstanceOf(PricatDecodeException.class)
                    .hasMessageContaining("vendor error code 42");
        }

        @Test
        void failsOnAnEmptyBodyRatherThanReportingAnEmptyCatalog() {
            assertThatThrownBy(() -> codec.decode("   ")).isInstanceOf(PricatDecodeException.class);
        }

        @Test
        void failsOnABodyThatIsNotACatalogDocument() {
            assertThatThrownBy(() -> codec.decode("<html>gateway timeout</html>"))
                    .isInstanceOf(PricatDecodeException.class)
                    .hasMessageContaining("not a B4.0 catalog document");
        }

        @Test
        void treatsAnArticleListWithNoLinesAsAnEmptyCatalogNotAFailure() {
            String body = """
                    {"envelopeHeader":{"documentId":"D1","countryCode":"SE","currency":"SEK","errorCode":"0"},
                     "articles":[]}
                    """;

            PricatDocument document = codec.decode(body);

            assertThat(document.entries()).isEmpty();
            assertThat(document.linesFetched()).isZero();
        }

        @Test
        void rejectsALineWhenTheHeaderStatesNoMarketScope() {
            String body = """
                    {"envelopeHeader":{"documentId":"D1","errorCode":"0"},
                     "articles":[{"pos":1,"ean":"3528709999083","grossPrice":"10.00","grossPriceValidFrom":"20260101"}]}
                    """;

            PricatDocument document = codec.decode(body);

            assertThat(document.entries()).isEmpty();
            assertThat(document.rejectedLines())
                    .singleElement()
                    .satisfies(line -> assertThat(line.detail()).contains("countryCode"));
        }

        @Test
        void rejectsALineWhenOnlyCurrencyIsMissing() {
            // The market-scope check is an OR of two absences; a country with no currency must be
            // rejected the same as the reverse.
            String body = """
                    {"envelopeHeader":{"documentId":"D1","countryCode":"SE","errorCode":"0"},
                     "articles":[{"pos":1,"ean":"3528709999083","grossPrice":"10.00","grossPriceValidFrom":"20260101"}]}
                    """;

            PricatDocument document = codec.decode(body);

            assertThat(document.entries()).isEmpty();
            assertThat(document.rejectedLines())
                    .singleElement()
                    .satisfies(line -> assertThat(line.detail()).contains("currency"));
        }

        @Test
        void rejectsABodyThatDecodesToNoDocument() {
            // A JSON literal "null" is readable per Jackson but is not a catalog document; nothing
            // downstream must confuse that with an empty catalog.
            assertThatThrownBy(() -> codec.decode("null"))
                    .isInstanceOf(PricatDecodeException.class)
                    .hasMessageContaining("decoded to no document");
        }

        @Test
        void toleratesADocumentWithNoEnvelopeHeaderAtAll() {
            // No envelopeHeader block at all - every header-derived fact must degrade to null rather
            // than throwing at the vendor.
            String body = """
                    {"articles":[]}
                    """;

            PricatDocument document = codec.decode(body);

            assertThat(document.documentId()).isNull();
            assertThat(document.documentDate()).isNull();
            assertThat(document.countryCode()).isNull();
            assertThat(document.currency()).isNull();
            assertThat(document.linesFetched()).isZero();
        }

        @Test
        void treatsAMissingArticlesArrayAsAnEmptyCatalogNotAFailure() {
            // The articles key can be absent entirely, distinct from an explicit empty array; both
            // must read as "nothing to import" rather than throw.
            String body = """
                    {"envelopeHeader":{"documentId":"D1","countryCode":"SE","currency":"SEK","errorCode":"0"}}
                    """;

            PricatDocument document = codec.decode(body);

            assertThat(document.entries()).isEmpty();
            assertThat(document.linesFetched()).isZero();
        }

        @Test
        void treatsANullArticleEntryAsARejectedLineNotACrash() {
            // A vendor emitting a literal null inside the articles array is a different failure mode
            // from an article that fails to parse; the line count is still accounted for.
            String body = """
                    {"envelopeHeader":{"documentId":"D1","countryCode":"SE","currency":"SEK","errorCode":"0"},
                     "articles":[null]}
                    """;

            PricatDocument document = codec.decode(body);

            assertThat(document.entries()).isEmpty();
            assertThat(document.rejectedLines()).singleElement().satisfies(line -> {
                assertThat(line.positionNumber()).isNull();
                assertThat(line.detail()).isEqualTo("null article line");
            });
            assertThat(document.linesFetched()).isEqualTo(1);
        }

        @Test
        void rejectsALineWithNoPriceAndNoValidityDateAtAllAndNoDocumentDateEither() {
            // Neither price carries a validity date, and the document header states none either: the
            // line has no effective date whatsoever and must be rejected rather than silently dated.
            String body = """
                    {"envelopeHeader":{"documentId":"D1","countryCode":"SE","currency":"SEK","errorCode":"0"},
                     "articles":[{"pos":1,"ean":"3528709999083"}]}
                    """;

            PricatDocument document = codec.decode(body);

            assertThat(document.entries()).isEmpty();
            assertThat(document.rejectedLines())
                    .singleElement()
                    .satisfies(line -> assertThat(line.detail()).contains("no effective date"));
        }

        @Test
        void fallsBackToTheDocumentDateWhenNeitherPriceStatesAValidityDate() throws IOException {
            // Both prices are stated but neither carries its own validity date; the document date is
            // the last fallback before the line is unusable.
            String body = """
                    {"envelopeHeader":{"documentId":"D1","date":"20260301","countryCode":"SE","currency":"SEK","errorCode":"0"},
                     "articles":[{"pos":1,"ean":"3528709999083","grossPrice":"10.00","netValue":"9.00"}]}
                    """;

            SupplierPriceCatalogEntry entry = codec.decode(body).entries().getFirst();

            assertThat(entry.effectiveFrom()).isEqualTo(LocalDate.of(2026, 3, 1));
        }

        @Test
        void rejectsANullBody() {
            assertThatThrownBy(() -> codec.decode(null)).isInstanceOf(PricatDecodeException.class);
        }

        @Test
        void rejectsALineWithAnUnparseableValidityDate() {
            // A validity date that will not parse as yyyyMMdd is a different failure from a missing
            // one: it is a vendor format change, and must reject the line rather than silently drop
            // the date.
            String body = """
                    {"envelopeHeader":{"documentId":"D1","countryCode":"SE","currency":"SEK","errorCode":"0"},
                     "articles":[{"pos":1,"ean":"3528709999083","grossPrice":"10.00","grossPriceValidFrom":"not-a-date"}]}
                    """;

            PricatDocument document = codec.decode(body);

            assertThat(document.entries()).isEmpty();
            assertThat(document.rejectedLines())
                    .singleElement()
                    .satisfies(line -> assertThat(line.detail()).contains("not a yyyyMMdd date"));
        }

        @Test
        void treatsAnUnparseableDocumentDateAsDescriptiveOnly() {
            // A header date that will not parse in either accepted form must not fail a whole
            // catalog: per-line validity dates carry the effective-dating contract.
            String body = """
                    {"envelopeHeader":{"documentId":"D1","date":"NOT-A-DATE","countryCode":"SE","currency":"SEK","errorCode":"0"},
                     "articles":[]}
                    """;

            PricatDocument document = codec.decode(body);

            assertThat(document.documentDate()).isNull();
        }

        @Test
        void fallsBackToANetValidityDateStatedWithNoNetPriceWhenGrossStatesNeitherEither() {
            // An unusual vendor shape: a net validity date is present but no net price is, and the
            // gross side states neither a price nor a date. The net date is still the last fallback
            // before the document date.
            String body = """
                    {"envelopeHeader":{"documentId":"D1","countryCode":"SE","currency":"SEK","errorCode":"0"},
                     "articles":[{"pos":1,"ean":"3528709999083","netValueValidFrom":"20260401"}]}
                    """;

            SupplierPriceCatalogEntry entry = codec.decode(body).entries().getFirst();

            assertThat(entry.effectiveFrom()).isEqualTo(LocalDate.of(2026, 4, 1));
        }

        @Test
        void fallsBackToTheGrossValidityDateWhenANetPriceIsStatedWithNoValidityDateOfItsOwn() {
            // A net price with no validity date of its own must not borrow the document date ahead
            // of a gross validity date that is actually stated.
            String body = """
                    {"envelopeHeader":{"documentId":"D1","countryCode":"SE","currency":"SEK","errorCode":"0"},
                     "articles":[{"pos":1,"ean":"3528709999083","netValue":"9.00","grossPrice":"10.00","grossPriceValidFrom":"20260215"}]}
                    """;

            SupplierPriceCatalogEntry entry = codec.decode(body).entries().getFirst();

            assertThat(entry.effectiveFrom()).isEqualTo(LocalDate.of(2026, 2, 15));
        }
    }
}
