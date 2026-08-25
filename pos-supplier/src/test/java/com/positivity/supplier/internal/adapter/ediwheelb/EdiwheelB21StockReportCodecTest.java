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

        @Test
        void omitsABlankAgencyCodeEvenWhenBuyerPartyIsSent() {
            // A stated-but-blank agency code is not the same as an absent one to the JSON reader,
            // but it must still be omitted from the query.
            SupplierRequestSpec spec = codec.buildRequest(new PartyContext("30012456", "  ", null), true);

            assertThat(spec.queryParams()).doesNotContainKey("agencyCode");
        }

        @Test
        void omitsTheAgencyCodeWhenTheAccountStatesNoneAtAll() {
            SupplierRequestSpec spec = codec.buildRequest(new PartyContext("30012456", null, null), true);

            assertThat(spec.queryParams()).doesNotContainKey("agencyCode");
            assertThat(spec.queryParams()).containsEntry("buyerParty", "30012456");
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

        @Test
        void rejectsABodyThatDecodesToNoDocument() {
            // A JSON literal "null" is a readable document per Jackson, but it is not a stock
            // report: nothing downstream of this must confuse "the vendor sent nothing" with an
            // empty envelope.
            assertThatThrownBy(() -> codec.decode("null"))
                    .isInstanceOf(StockReportDecodeException.class)
                    .hasMessageContaining("decoded to no document");
        }

        @Test
        void toleratesADocumentWithNoEnvelopeHeaderAtAll() {
            // No envelopeHeader block whatsoever - every header-derived fact must degrade to null
            // rather than throwing a NullPointerException at the vendor.
            String body = """
                    {"lineLevel":[]}
                    """;

            SupplierStockSnapshot snapshot = codec.decode(body);

            assertThat(snapshot.documentId()).isNull();
            assertThat(snapshot.issuedOn()).isNull();
            assertThat(snapshot.issuedAt()).isNull();
            assertThat(snapshot.buyerParty()).isNull();
            assertThat(snapshot.linesFetched()).isZero();
        }

        @Test
        void treatsANullLineEntryAsARejectedLineNotACrash() {
            // A vendor emitting a literal null inside the lineLevel array is a different failure
            // mode from a line that fails to parse: the line count is still accounted for.
            String body = """
                    {"envelopeHeader":{"issueDate":"20260814","documentId":"D1","variant":"B2_1","errorCode":"0"},
                     "lineLevel":[null]}
                    """;

            SupplierStockSnapshot snapshot = codec.decode(body);

            assertThat(snapshot.lines()).isEmpty();
            assertThat(snapshot.rejectedLines()).singleElement().satisfies(line -> {
                assertThat(line.lineId()).isNull();
                assertThat(line.detail()).isEqualTo("null stock report line");
            });
            assertThat(snapshot.linesFetched()).isEqualTo(1);
        }

        @Test
        void rejectsALineWithNoArticleBlockAtAll() {
            // Missing the article block entirely is a different vendor shape from an article with an
            // empty identity block, but both mean "no identity was stated" and must reject the same
            // way.
            String body = """
                    {"envelopeHeader":{"issueDate":"20260814","documentId":"D1","variant":"B2_1","errorCode":"0"},
                     "lineLevel":[{"lineId":"9"}]}
                    """;

            SupplierStockSnapshot snapshot = codec.decode(body);

            assertThat(snapshot.rejectedLines())
                    .singleElement()
                    .satisfies(line -> assertThat(line.detail()).contains("no article identity"));
        }

        @Test
        void leavesDescriptionAndQuantityNullWhenTheVendorStatesIdentityButNothingElse() {
            // A line can carry an identity with neither a description nor an availability block. Both
            // must degrade to null, distinctly from the "stated but blank" case covered elsewhere.
            String body = """
                    {"envelopeHeader":{"issueDate":"20260814","documentId":"D1","variant":"B2_1","errorCode":"0"},
                     "lineLevel":[{"lineId":"1","article":{"articleIdentification":{"eanuccarticleID":"3528709999083"}}}]}
                    """;

            SupplierStockSnapshot.Line line = codec.decode(body).lines().getFirst();

            assertThat(line.articleEan()).isEqualTo("3528709999083");
            assertThat(line.description()).isNull();
            assertThat(line.availableQuantity()).isNull();
        }

        @Test
        void rejectsAQuantityWithAFractionalRemainder() {
            // "12.5" is a well-formed decimal but not a whole tyre count - it must be rejected rather
            // than silently truncated, which would misreport availability.
            String body = """
                    {"envelopeHeader":{"issueDate":"20260814","documentId":"D1","variant":"B2_1","errorCode":"0"},
                     "lineLevel":[{"lineId":"1","article":{"articleIdentification":{"eanuccarticleID":"3528709999083"},
                       "availableQuantity":{"quantityValue":"12.5"}}}]}
                    """;

            SupplierStockSnapshot snapshot = codec.decode(body);

            assertThat(snapshot.rejectedLines())
                    .singleElement()
                    .satisfies(line -> assertThat(line.detail()).contains("not a whole number"));
        }

        @Test
        void leavesIssuedOnNullWhenTheHeaderStatesNoIssueDate() {
            // The header block is present but states no issue date at all - descriptive-only, must
            // not fail the document.
            String body = """
                    {"envelopeHeader":{"documentId":"D1","variant":"B2_1","errorCode":"0"},
                     "lineLevel":[]}
                    """;

            SupplierStockSnapshot snapshot = codec.decode(body);

            assertThat(snapshot.issuedOn()).isNull();
            assertThat(snapshot.issuedAt()).isNull();
        }

        @Test
        void treatsAnUnparseableIssueDateAsDescriptiveOnly() {
            // A header date that will not parse in either accepted form must not fail a whole
            // document: the import's own fetch timestamp still bounds the snapshot.
            String body = """
                    {"envelopeHeader":{"issueDate":"NEXT-TUESDAY","documentId":"D1","variant":"B2_1","errorCode":"0"},
                     "lineLevel":[]}
                    """;

            SupplierStockSnapshot snapshot = codec.decode(body);

            assertThat(snapshot.issuedOn()).isNull();
            assertThat(snapshot.issuedAt()).isNull();
        }

        @Test
        void acceptsAnIsoFormattedIssueDateAsAFallback() {
            // Not every vendor deployment sticks to the norm's yyyyMMdd example; ISO-8601 is accepted
            // as a fallback rather than rejected outright.
            String body = """
                    {"envelopeHeader":{"issueDate":"2026-08-14","documentId":"D1","variant":"B2_1","errorCode":"0"},
                     "lineLevel":[]}
                    """;

            SupplierStockSnapshot snapshot = codec.decode(body);

            assertThat(snapshot.issuedOn()).isEqualTo(LocalDate.of(2026, 8, 14));
        }

        @Test
        void readsALongFormIssueTimeWithSeconds() {
            // HHmmss is the other time form the norm allows alongside HHmm.
            String body = """
                    {"envelopeHeader":{"issueDate":"20260814","issueTime":"060030","documentId":"D1","variant":"B2_1","errorCode":"0"},
                     "lineLevel":[]}
                    """;

            SupplierStockSnapshot snapshot = codec.decode(body);

            assertThat(snapshot.issuedAt()).isEqualTo(Instant.parse("2026-08-14T06:00:30Z"));
        }

        @Test
        void rejectsANullBody() {
            assertThatThrownBy(() -> codec.decode(null)).isInstanceOf(StockReportDecodeException.class);
        }

        @Test
        void toleratesADocumentWithNoLineLevelKeyAtAll() {
            // The lineLevel array can be missing entirely, distinct from an explicit empty array;
            // both must read as "nothing reported" rather than throw.
            String body = """
                    {"envelopeHeader":{"issueDate":"20260814","documentId":"D1","variant":"B2_1","errorCode":"0"}}
                    """;

            SupplierStockSnapshot snapshot = codec.decode(body);

            assertThat(snapshot.lines()).isEmpty();
            assertThat(snapshot.linesFetched()).isZero();
        }

        @Test
        void acceptsAnIdentityStatedBySupplierCodeAlone() {
            // The three identity fields are an OR of presence, not a required trio; a supplier code
            // on its own is enough to accept the line.
            String body = """
                    {"envelopeHeader":{"issueDate":"20260814","documentId":"D1","variant":"B2_1","errorCode":"0"},
                     "lineLevel":[{"lineId":"1","article":{"articleIdentification":{"manufacturersArticleId":"999908"}}}]}
                    """;

            SupplierStockSnapshot.Line line = codec.decode(body).lines().getFirst();

            assertThat(line.supplierArticleCode()).isEqualTo("999908");
            assertThat(line.articleEan()).isNull();
        }

        @Test
        void acceptsAnIdentityStatedByTheBuyersArticleIdAlone() {
            // Same OR of presence, exercised on the third identity field: neither an EAN nor a
            // supplier code is required when the buyer's own article id is stated.
            //
            // This is also the last combination of the identity check's three-way AND that a golden
            // or degraded-vendor fixture can reach without going through the fully-null case above:
            // {@code article == null} is unreachable at the description/quantity ternaries in
            // {@code toLine} precisely because reaching them requires this identity check to have
            // passed, which requires {@code article} to be non-null.
            String body = """
                    {"envelopeHeader":{"issueDate":"20260814","documentId":"D1","variant":"B2_1","errorCode":"0"},
                     "lineLevel":[{"lineId":"1","article":{"articleIdentification":{"buyersArticleId":"BUY-1"}}}]}
                    """;

            SupplierStockSnapshot.Line line = codec.decode(body).lines().getFirst();

            assertThat(line.buyersArticleId()).isEqualTo("BUY-1");
            assertThat(line.articleEan()).isNull();
            assertThat(line.supplierArticleCode()).isNull();
        }

        @Test
        void treatsAnUnparseableIssueTimeAsAbsentWithoutInventingOne() {
            // An issue time that will not parse must not fail the document, and must not silently
            // become midnight: it degrades to null like an absent time does.
            String body = """
                    {"envelopeHeader":{"issueDate":"20260814","issueTime":"SOMETIME","documentId":"D1","variant":"B2_1","errorCode":"0"},
                     "lineLevel":[]}
                    """;

            SupplierStockSnapshot snapshot = codec.decode(body);

            assertThat(snapshot.issuedOn()).isEqualTo(LocalDate.of(2026, 8, 14));
            assertThat(snapshot.issuedAt()).isNull();
        }
    }
}
