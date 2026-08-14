package com.positivity.supplier.internal.adapter.ediwheelb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.domain.model.SupplierStockSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("EDIWheel Stock Report B2.1 codec (#1228)")
class EdiwheelB21StockReportCodecTest {

    private final EdiwheelB21StockReportCodec codec = new EdiwheelB21StockReportCodec(new ObjectMapper());

    private static String goldenDocument() throws IOException {
        try (InputStream in =
                EdiwheelB21StockReportCodecTest.class.getResourceAsStream("/fixtures/stock-report-b21-sample.json")) {
            assertThat(in)
                    .as("golden stock-report fixture must be on the test classpath")
                    .isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void registersUnderTheStockReportTriple() {
        assertThat(codec.capability()).isEqualTo(SupplierCapability.STOCK_REPORT);
        assertThat(codec.family()).isEqualTo(ProtocolFamily.EDIWHEEL_B);
        assertThat(codec.version()).isEqualTo(ProtocolVersion.B2_1);
    }

    @Nested
    @DisplayName("buildRequest")
    class BuildRequest {

        @Test
        void sendsNoQueryParametersForB21() {
            SupplierRequestSpec spec = codec.buildRequest(new PartyContext("30012456", "91", null), false);

            assertThat(spec.method()).isEqualTo("GET");
            assertThat(spec.queryParams()).isEmpty();
            assertThat(spec.accept()).isEqualTo("application/json");
        }

        @Test
        void sendsBuyerIdentificationWhenTheBindingIsAC10Endpoint() {
            SupplierRequestSpec spec = codec.buildRequest(new PartyContext("30012456", "91", null), true);

            assertThat(spec.queryParams())
                    .containsEntry("buyerParty", "30012456")
                    .containsEntry("agencyCode", "91");
        }

        @Test
        void isRetryableBecauseASnapshotReadHasNoCommercialEffect() {
            assertThat(codec.buildRequest(new PartyContext("30012456", null, null), false)
                            .idempotent())
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("decode")
    class Decode {

        @Test
        void readsTheVendorsOwnSnapshotTimeSeparatelyFromTheDocumentDate() throws IOException {
            SupplierStockSnapshot snapshot = codec.decode(goldenDocument());

            assertThat(snapshot.documentId()).isEqualTo("STOCKREPORT-4046266_20260814");
            assertThat(snapshot.issuedOn()).isEqualTo(LocalDate.of(2026, 8, 14));
            assertThat(snapshot.issuedAt()).isEqualTo(Instant.parse("2026-08-14T06:00:00Z"));
            assertThat(snapshot.buyerParty()).isEqualTo("30012456");
        }

        @Test
        void mapsArticleIdentityAndQuantity() throws IOException {
            SupplierStockSnapshot.Line first =
                    codec.decode(goldenDocument()).lines().getFirst();

            assertThat(first.lineId()).isEqualTo("1");
            assertThat(first.articleEan()).isEqualTo("3528709999083");
            assertThat(first.supplierArticleCode()).isEqualTo("999908");
            assertThat(first.buyersArticleId()).isEqualTo("BUY-1");
            assertThat(first.description()).isEqualTo("255/55R19 111VXL PS4 SUV");
            assertThat(first.availableQuantity()).isEqualTo(42);
        }

        @Test
        void keepsAnExplicitZeroDistinctFromAnUnstatedQuantity() throws IOException {
            var lines = codec.decode(goldenDocument()).lines();

            // Line 2 says zero: the vendor reported it has none.
            assertThat(lines.get(1).availableQuantity()).isZero();
            // Line 3 says nothing: the vendor mentioned the article without stating a quantity, and
            // that must not become zero.
            assertThat(lines.get(2).availableQuantity()).isNull();
        }

        @Test
        void rejectsALineWithNoArticleIdentityWithoutDiscardingTheSnapshot() throws IOException {
            SupplierStockSnapshot snapshot = codec.decode(goldenDocument());

            assertThat(snapshot.lines()).hasSize(3);
            assertThat(snapshot.rejectedLines()).singleElement().satisfies(line -> {
                assertThat(line.lineId()).isEqualTo("4");
                assertThat(line.detail()).contains("no article identity");
            });
            assertThat(snapshot.lines().size() + snapshot.rejectedLines().size())
                    .isEqualTo(snapshot.linesFetched());
        }

        @Test
        void rejectsAnUnparseableQuantityAsALineRatherThanADocument() {
            String body = """
                    {"envelopeHeader":{"issueDate":"20260814","documentId":"D1","variant":"B2_1","errorCode":"0"},
                     "lineLevel":[{"lineId":"1","article":{"articleIdentification":{"eanuccarticleID":"3528709999083"},
                       "availableQuantity":{"quantityValue":"lots"}}}]}
                    """;

            SupplierStockSnapshot snapshot = codec.decode(body);

            assertThat(snapshot.lines()).isEmpty();
            assertThat(snapshot.rejectedLines())
                    .singleElement()
                    .satisfies(line -> assertThat(line.detail()).contains("not a number"));
        }

        @Test
        void acceptsAWholeQuantityStatedWithADecimalPart() {
            String body = """
                    {"envelopeHeader":{"issueDate":"20260814","documentId":"D1","variant":"B2_1","errorCode":"0"},
                     "lineLevel":[{"lineId":"1","article":{"articleIdentification":{"eanuccarticleID":"3528709999083"},
                       "availableQuantity":{"quantityValue":"12.000"}}}]}
                    """;

            assertThat(codec.decode(body).lines().getFirst().availableQuantity())
                    .isEqualTo(12);
        }

        @Test
        void failsTheDocumentWhenTheVendorSignalsAnErrorCode() {
            String body = """
                    {"envelopeHeader":{"issueDate":"20260814","documentId":"D1","variant":"B2_1","errorCode":"42"},
                     "lineLevel":[]}
                    """;

            assertThatThrownBy(() -> codec.decode(body))
                    .isInstanceOf(StockReportDecodeException.class)
                    .hasMessageContaining("vendor error code 42");
        }

        @Test
        void failsOnAnEmptyOrNonDocumentBody() {
            assertThatThrownBy(() -> codec.decode("   ")).isInstanceOf(StockReportDecodeException.class);
            assertThatThrownBy(() -> codec.decode("<html>gateway timeout</html>"))
                    .isInstanceOf(StockReportDecodeException.class)
                    .hasMessageContaining("not a B2.1 document");
        }

        @Test
        void treatsALineLessDocumentAsAnEmptySnapshotNotAFailure() {
            String body = """
                    {"envelopeHeader":{"issueDate":"20260814","documentId":"D1","variant":"B2_1","errorCode":"0"},
                     "lineLevel":[]}
                    """;

            SupplierStockSnapshot snapshot = codec.decode(body);

            assertThat(snapshot.lines()).isEmpty();
            assertThat(snapshot.linesFetched()).isZero();
        }

        @Test
        void toleratesAHeaderWithNoTimeWithoutInventingOne() {
            String body = """
                    {"envelopeHeader":{"issueDate":"20260814","documentId":"D1","variant":"B2_1","errorCode":"0"},
                     "lineLevel":[]}
                    """;

            SupplierStockSnapshot snapshot = codec.decode(body);

            assertThat(snapshot.issuedOn()).isEqualTo(LocalDate.of(2026, 8, 14));
            assertThat(snapshot.issuedAt()).isNull();
        }
    }
}
