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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("EDIWheel PRICAT B4.0 codec (#1224)")
class EdiwheelB40PricatCodecTest {

    private static final UUID MANIFEST_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");

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
    }

    @Nested
    @DisplayName("decode")
    class Decode {

        @Test
        void readsHeaderFactsFromTheGoldenDocument() throws IOException {
            PricatDocument document = codec.decode(goldenDocument(), MANIFEST_ID);

            assertThat(document.documentId()).isEqualTo("PRICAT-4046266_202305120844");
            assertThat(document.documentDate()).isEqualTo(LocalDate.of(2023, 5, 12));
            assertThat(document.countryCode()).isEqualTo("SE");
            assertThat(document.currency()).isEqualTo("SEK");
            assertThat(document.linesFetched()).isEqualTo(4);
        }

        @Test
        void mapsIdentityPricesAndFeesVerbatim() throws IOException {
            SupplierPriceCatalogEntry first =
                    codec.decode(goldenDocument(), MANIFEST_ID).entries().getFirst();

            assertThat(first.articleEan()).isEqualTo("3528709999083");
            assertThat(first.supplierArticleCode()).isEqualTo("999908");
            assertThat(first.suggestedRetailPrice()).isEqualByComparingTo(new BigDecimal("3195.00"));
            assertThat(first.grossPrice()).isEqualByComparingTo(new BigDecimal("2895.50"));
            assertThat(first.netPrice()).isEqualByComparingTo(new BigDecimal("2450.25"));
            assertThat(first.taxRate()).isEqualByComparingTo(new BigDecimal("25"));
            assertThat(first.recyclingFee()).isEqualByComparingTo(new BigDecimal("35.00"));
            assertThat(first.countryCode()).isEqualTo("SE");
            assertThat(first.currency()).isEqualTo("SEK");
            assertThat(first.importManifestId()).isEqualTo(MANIFEST_ID);
            assertThat(first.positionNumber()).isEqualTo(1);
        }

        @Test
        void prefersTheNetValidityDateWhenANetPriceIsStated() throws IOException {
            SupplierPriceCatalogEntry first =
                    codec.decode(goldenDocument(), MANIFEST_ID).entries().getFirst();

            // Gross says 2021-01-01, net says 2026-02-01; the net price is what a buyer pays.
            assertThat(first.effectiveFrom()).isEqualTo(LocalDate.of(2026, 2, 1));
        }

        @Test
        void fallsBackToTheGrossValidityDateWhenNoNetPriceIsStated() throws IOException {
            SupplierPriceCatalogEntry second =
                    codec.decode(goldenDocument(), MANIFEST_ID).entries().get(1);

            assertThat(second.netPrice()).isNull();
            assertThat(second.effectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 15));
        }

        @Test
        void acceptsCommaDecimalsAsSomeMarketsStateThem() throws IOException {
            SupplierPriceCatalogEntry second =
                    codec.decode(goldenDocument(), MANIFEST_ID).entries().get(1);

            assertThat(second.grossPrice()).isEqualByComparingTo(new BigDecimal("1450.00"));
        }

        @Test
        void carriesTheCrossReferenceCodeAsASecondIdentity() throws IOException {
            SupplierPriceCatalogEntry second =
                    codec.decode(goldenDocument(), MANIFEST_ID).entries().get(1);

            assertThat(second.articleEan()).isNull();
            assertThat(second.xReferenceCode()).isEqualTo("0123456789012");
        }

        @Test
        void rejectsUnusableLinesWithoutDiscardingTheDocument() throws IOException {
            PricatDocument document = codec.decode(goldenDocument(), MANIFEST_ID);

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

            assertThatThrownBy(() -> codec.decode(body, MANIFEST_ID))
                    .isInstanceOf(PricatDecodeException.class)
                    .hasMessageContaining("vendor error code 42");
        }

        @Test
        void failsOnAnEmptyBodyRatherThanReportingAnEmptyCatalog() {
            assertThatThrownBy(() -> codec.decode("   ", MANIFEST_ID)).isInstanceOf(PricatDecodeException.class);
        }

        @Test
        void failsOnABodyThatIsNotACatalogDocument() {
            assertThatThrownBy(() -> codec.decode("<html>gateway timeout</html>", MANIFEST_ID))
                    .isInstanceOf(PricatDecodeException.class)
                    .hasMessageContaining("not a B4.0 catalog document");
        }

        @Test
        void treatsAnArticleListWithNoLinesAsAnEmptyCatalogNotAFailure() {
            String body = """
                    {"envelopeHeader":{"documentId":"D1","countryCode":"SE","currency":"SEK","errorCode":"0"},
                     "articles":[]}
                    """;

            PricatDocument document = codec.decode(body, MANIFEST_ID);

            assertThat(document.entries()).isEmpty();
            assertThat(document.linesFetched()).isZero();
        }

        @Test
        void rejectsALineWhenTheHeaderStatesNoMarketScope() {
            String body = """
                    {"envelopeHeader":{"documentId":"D1","errorCode":"0"},
                     "articles":[{"pos":1,"ean":"3528709999083","grossPrice":"10.00","grossPriceValidFrom":"20260101"}]}
                    """;

            PricatDocument document = codec.decode(body, MANIFEST_ID);

            assertThat(document.entries()).isEmpty();
            assertThat(document.rejectedLines())
                    .singleElement()
                    .satisfies(line -> assertThat(line.detail()).contains("countryCode"));
        }
    }
}
