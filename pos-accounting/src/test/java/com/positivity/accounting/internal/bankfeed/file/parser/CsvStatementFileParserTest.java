package com.positivity.accounting.internal.bankfeed.file.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.bankfeed.file.parser.ColumnMapping.ColumnRef;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The CSV statement parser (SPEC-manual-bank-reconciliation §4.3, §4.4, G8, G10; story S3, #2302),
 * including the F2 behaviours carried over from {@code BankReconciliationServiceTest}.
 */
@DisplayName("CsvStatementFileParser (#2302)")
class CsvStatementFileParserTest {

    private final CsvStatementFileParser parser = new CsvStatementFileParser();

    private ParsedFile parse(String text) {
        return parser.parse(text.getBytes(StandardCharsets.UTF_8), ParserOptions.defaults(), null);
    }

    private ParsedFile parse(String text, ParserOptions options, ColumnMapping mapping) {
        return parser.parse(text.getBytes(StandardCharsets.UTF_8), options, mapping);
    }

    private static ParserOptions convention(SignConvention convention) {
        return ParserOptions.of(null, null, null, null, convention.name());
    }

    @Nested
    @DisplayName("F2 behaviours carried over")
    class Carried {

        @Test
        void aHeaderRowIsDetectedAndParenthesesAreNegative() {
            ParsedFile file = parse("date,description,amount,reference\n2026-06-15,ACH DEPOSIT,1500.00,REF-1\n"
                    + "2026-06-20,SERVICE FEE,(12.50),REF-2");

            assertThat(file.headerRow()).isTrue();
            assertThat(file.mappingResolved()).isTrue();
            assertThat(file.columns()).containsExactly("date", "description", "amount", "reference");
            assertThat(file.rows()).hasSize(2);
            assertThat(file.rows().get(0).signedAmount()).isEqualByComparingTo("1500.00");
            assertThat(file.rows().get(0).reference()).isEqualTo("REF-1");
            assertThat(file.rows().get(1).signedAmount()).isEqualByComparingTo("-12.50");
            assertThat(file.rows())
                    .extracting(ParsedRow::rowNumber, ParsedRow::lineNumber)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(1, 2), org.assertj.core.groups.Tuple.tuple(2, 3));
        }

        @Test
        void aQuotedDescriptionKeepsItsEmbeddedComma() {
            assertThat(parse("2026-06-15,\"ACME, INC\",100.00,REF-1")
                            .rows()
                            .get(0)
                            .description())
                    .isEqualTo("ACME, INC");
        }

        @Test
        void aDoubledQuoteInsideAQuotedFieldIsALiteralQuote() {
            assertThat(parse("2026-06-15,\"Joe\"\"s Diner\",50.00,REF-1")
                            .rows()
                            .get(0)
                            .description())
                    .isEqualTo("Joe\"s Diner");
        }

        @Test
        void aQuotedLastFieldEndsTheLine() {
            assertThat(parse("2026-06-15,ACH DEPOSIT,100.00,\"REF-1\"")
                            .rows()
                            .get(0)
                            .reference())
                    .isEqualTo("REF-1");
        }

        @Test
        void usDatesDollarSignsAndThousandsSeparatorsAreRead() {
            ParsedRow row = parse("6/5/2026,Deposit,\"$1,234.56\"").rows().get(0);
            assertThat(row.date()).isEqualTo(LocalDate.of(2026, 6, 5));
            assertThat(row.signedAmount()).isEqualByComparingTo("1234.56");
        }

        @Test
        void base64TextIsDecoded() {
            String csv = "date,description,amount\n2026-06-15,Deposit,100.00\n";
            ParsedFile file = parse(Base64.getEncoder().encodeToString(csv.getBytes(StandardCharsets.UTF_8)));
            assertThat(file.rows())
                    .singleElement()
                    .satisfies(row -> assertThat(row.description()).isEqualTo("Deposit"));
        }

        @Test
        void aHeaderlessFirstRowWithABadDateIsRejectedNotDroppedAndNotTheWholeFile() {
            ParsedFile file = parse("2026-13-45,BAD DATE,100.00,REF-1\n2026-06-16,GOOD,5.00,REF-2");

            assertThat(file.headerRow()).isFalse();
            assertThat(file.rows()).hasSize(2);
            assertThat(file.rows().get(0).rejection()).isEqualTo(RejectionCode.DATE_UNPARSEABLE);
            assertThat(file.rows().get(0).rejectionDetail()).contains("Line 1").contains("2026-13-45");
            assertThat(file.rows().get(1).rejected()).isFalse();
        }

        @Test
        void aMalformedAmountRejectsTheRowOnly() {
            ParsedFile file = parse("2026-06-15,ACH DEPOSIT,NOT_A_NUMBER,REF-1\n2026-06-16,FEE,-1.00,REF-2");
            assertThat(file.rows().get(0).rejection()).isEqualTo(RejectionCode.AMOUNT_UNPARSEABLE);
            assertThat(file.rows().get(1).signedAmount()).isEqualByComparingTo("-1.00");
        }
    }

    @Nested
    @DisplayName("unreadable files (G10: 422 STATEMENT_IMPORT_FAILED)")
    class Unreadable {

        @Test
        void anEmptyFile() {
            assertFailed(new byte[0], "empty");
        }

        @Test
        void aFileWithOnlyBlankLines() {
            assertFailed("\n  \n".getBytes(StandardCharsets.UTF_8), "no rows");
        }

        @Test
        void aHeaderWithoutData() {
            assertFailed("date,description,amount\n".getBytes(StandardCharsets.UTF_8), "no data rows");
        }

        @Test
        void aBinaryFile() {
            assertFailed(new byte[] {'P', 'K', 3, 4, 0, 0, 1}, "binary");
        }

        @Test
        void bytesThatAreNotTextInTheEncoding() {
            assertFailed(new byte[] {(byte) 0xC3, (byte) 0x28, ',', '1'}, "UTF-8");
        }

        @Test
        void anUnterminatedQuotedFieldRatherThanOneRecordSwallowingTheRest() {
            // A stray quote must not fold every later row into one cell of row 2.
            assertFailed(
                    ("date,description,amount\n2026-06-15,\"ACME, INC,100.00\n2026-06-16,FEE,-1.00\n"
                                    + "2026-06-17,DEP,5.00\n")
                            .getBytes(StandardCharsets.UTF_8),
                    "Line 2: a quoted field is not closed");
        }

        private void assertFailed(byte[] content, String message) {
            assertThatThrownBy(() -> parser.parse(content, ParserOptions.defaults(), null))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.STATEMENT_IMPORT_FAILED))
                    .hasMessageContaining(message);
        }
    }

    @Nested
    @DisplayName("column mapping")
    class Mapping {

        private static final String BANK_EXPORT =
                "Posted Date,Payee,Amount USD,Ref,Check,Bank Id\n" + "2026-06-15,ACME,100.00,R1,1001,TX-1\n";

        @Test
        void headerNamesThatDifferFromTheDefaultsLeaveTheMappingUnresolved() {
            ParsedFile file = parse(BANK_EXPORT);

            assertThat(file.mappingResolved()).isFalse();
            assertThat(file.unresolvedColumns()).containsExactly("date", "description", "amount");
            assertThat(file.columns()).containsExactly("Posted Date", "Payee", "Amount USD", "Ref", "Check", "Bank Id");
            assertThat(file.rows()).singleElement().satisfies(row -> {
                assertThat(row.rejection()).isEqualTo(RejectionCode.REQUIRED_COLUMN_MISSING);
                assertThat(row.rawValues()).containsEntry("Payee", "ACME").containsEntry("Amount USD", "100.00");
            });
        }

        @Test
        void aMappingByHeaderNameIgnoresCase() {
            ColumnMapping mapping = ColumnMapping.fromJson(
                    Map.of(
                            "date", "posted date",
                            "description", "PAYEE",
                            "amount", "Amount USD",
                            "reference", "Ref",
                            "checkNumber", "Check",
                            "sourceTransactionId", "Bank Id"),
                    "columnMapping");
            ParsedRow row =
                    parse(BANK_EXPORT, ParserOptions.defaults(), mapping).rows().get(0);

            assertThat(row.rejected()).isFalse();
            assertThat(row.description()).isEqualTo("ACME");
            assertThat(row.reference()).isEqualTo("R1");
            assertThat(row.checkNumber()).isEqualTo("1001");
            assertThat(row.sourceTransactionId()).isEqualTo("TX-1");
        }

        @Test
        void aMappingByPositionIsZeroBased() {
            ColumnMapping mapping =
                    new ColumnMapping(ColumnRef.at(2), ColumnRef.at(0), ColumnRef.at(1), null, null, null, null, null);
            ParsedRow row = parse("Payee,-7.25,2026-06-15", ParserOptions.defaults(), mapping)
                    .rows()
                    .get(0);

            assertThat(row.date()).isEqualTo(LocalDate.of(2026, 6, 15));
            assertThat(row.description()).isEqualTo("Payee");
            assertThat(row.signedAmount()).isEqualByComparingTo("-7.25");
        }

        @Test
        void aPositionBeyondTheFileIsUnresolved() {
            ColumnMapping mapping =
                    new ColumnMapping(ColumnRef.at(0), ColumnRef.at(1), ColumnRef.at(9), null, null, null, null, null);
            assertThat(parse("2026-06-15,Deposit,1.00", ParserOptions.defaults(), mapping)
                            .unresolvedColumns())
                    .containsExactly("amount");
        }

        @Test
        void anUnknownKeyOrABadValueIs400() {
            assertThatThrownBy(() -> ColumnMapping.fromJson(Map.of("dat", "x", "amount", -1), "columnMapping"))
                    .isInstanceOfSatisfying(BankRecException.class, e -> {
                        assertThat(e.code()).isEqualTo(BankRecErrorCode.VALIDATION_ERROR);
                        assertThat(e.fieldErrors()).containsKeys("columnMapping.dat", "columnMapping.amount");
                    });
        }

        @Test
        void theJsonFormRoundTrips() {
            Map<String, Object> json = Map.of("date", "Posted", "description", 1, "debit", "Out", "credit", "In");
            assertThat(ColumnMapping.fromJson(json, "m").toJson()).isEqualTo(json);
        }
    }

    @Nested
    @DisplayName("sign conventions (§4.4): stored amounts are positive = cash in")
    class Conventions {

        @Test
        void signedAmountInvertedNegatesEveryAmount() {
            List<ParsedRow> rows = parse(
                            "date,description,amount\n2026-06-15,WITHDRAWAL,40.00\n2026-06-16,DEPOSIT,-100.00",
                            convention(SignConvention.SIGNED_AMOUNT_INVERTED),
                            null)
                    .rows();
            assertThat(rows.get(0).signedAmount()).isEqualByComparingTo("-40.00");
            assertThat(rows.get(1).signedAmount()).isEqualByComparingTo("100.00");
        }

        @Test
        void debitCreditColumnsMakeCreditsPositiveAndDebitsNegative() {
            ColumnMapping mapping = ColumnMapping.fromJson(
                    Map.of("date", "date", "description", "description", "debit", "debit", "credit", "credit"),
                    "columnMapping");
            List<ParsedRow> rows = parse(
                            "date,description,debit,credit\n2026-06-15,FEE,15.00,\n2026-06-16,DEPOSIT,,500.00\n"
                                    + "2026-06-17,CHECK,-20.00,0.00",
                            convention(SignConvention.DEBIT_CREDIT_COLUMNS),
                            mapping)
                    .rows();
            assertThat(rows)
                    .extracting(ParsedRow::signedAmount)
                    .usingElementComparator(java.math.BigDecimal::compareTo)
                    .containsExactly(
                            new java.math.BigDecimal("-15.00"),
                            new java.math.BigDecimal("500.00"),
                            new java.math.BigDecimal("-20.00"));
        }

        @Test
        void debitCreditColumnsRequireBothToBeMapped() {
            assertThat(parse(
                                    "date,description,amount\n2026-06-15,FEE,1.00",
                                    convention(SignConvention.DEBIT_CREDIT_COLUMNS),
                                    null)
                            .unresolvedColumns())
                    .containsExactly("debit", "credit");
        }
    }

    @Nested
    @DisplayName("row rejection codes (G8)")
    class Rejections {

        private final ColumnMapping debitCredit = ColumnMapping.fromJson(
                Map.of("date", "date", "description", "description", "debit", "debit", "credit", "credit"),
                "columnMapping");

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource(
                delimiter = '|',
                value = {
                    "2026-02-30,X,1.00|DATE_UNPARSEABLE",
                    "2026-06-15,X,1..0|AMOUNT_UNPARSEABLE",
                    "2026-06-15,X,1.00001|AMOUNT_UNPARSEABLE",
                    "2026-06-15,X,0.00|AMOUNT_ZERO",
                    "2026-06-15,X,|REQUIRED_COLUMN_MISSING",
                    "2026-06-15,,1.00|REQUIRED_COLUMN_MISSING",
                    ",X,1.00|REQUIRED_COLUMN_MISSING",
                    "2026-06-15,X|REQUIRED_COLUMN_MISSING"
                })
        void signedAmountRows(String line, RejectionCode expected) {
            ParsedRow row = parse("date,description,amount\n" + line + "\n2026-06-01,OK,1.00")
                    .rows()
                    .get(0);
            assertThat(row.rejection()).isEqualTo(expected);
            assertThat(row.rejectionDetail()).startsWith("Line 2");
        }

        @Test
        void bothDebitAndCredit() {
            ParsedRow row = parse(
                            "date,description,debit,credit\n2026-06-15,X,1.00,2.00",
                            convention(SignConvention.DEBIT_CREDIT_COLUMNS),
                            debitCredit)
                    .rows()
                    .get(0);
            assertThat(row.rejection()).isEqualTo(RejectionCode.AMOUNT_AND_DEBIT_CREDIT_BOTH);
        }

        @Test
        void neitherDebitNorCredit() {
            ParsedRow row = parse(
                            "date,description,debit,credit\n2026-06-15,X,,",
                            convention(SignConvention.DEBIT_CREDIT_COLUMNS),
                            debitCredit)
                    .rows()
                    .get(0);
            assertThat(row.rejection()).isEqualTo(RejectionCode.REQUIRED_COLUMN_MISSING);
        }
    }

    @Nested
    @DisplayName("parser options")
    class Options {

        @Test
        void aDateFormatAndADecimalCommaAndASemicolonDelimiter() {
            ParserOptions options = ParserOptions.of(null, ";", "dd.MM.yyyy", "DECIMAL_COMMA", null);
            ParsedRow row = parse(
                            "Datum;Text;Betrag\n15.06.2026;Miete;\"-1.234,50\"",
                            options,
                            ColumnMapping.fromJson(
                                    Map.of("date", "Datum", "description", "Text", "amount", "Betrag"), "m"))
                    .rows()
                    .get(0);
            assertThat(row.date()).isEqualTo(LocalDate.of(2026, 6, 15));
            assertThat(row.signedAmount()).isEqualByComparingTo("-1234.50");
        }

        @Test
        void aTabDelimiterAndALatin1File() {
            ParserOptions options = ParserOptions.of("ISO-8859-1", "TAB", null, null, null);
            byte[] content = "2026-06-15\tCafé\t3.50".getBytes(StandardCharsets.ISO_8859_1);
            ParsedRow row = parser.parse(content, options, null).rows().get(0);
            assertThat(row.description()).isEqualTo("Café");
        }

        @Test
        void aQuotedFieldMaySpanLines() {
            ParsedFile file = parse("date,description,amount\n2026-06-15,\"TWO\nLINES\",1.00\n2026-06-16,NEXT,2.00");
            assertThat(file.rows()).hasSize(2);
            assertThat(file.rows().get(0).description()).isEqualTo("TWO\nLINES");
            assertThat(file.rows().get(1).lineNumber()).isEqualTo(4);
        }

        @Test
        void invalidOptionsAre400NamingEachField() {
            assertThatThrownBy(() -> ParserOptions.of("NOPE-8", "ab", "yyyy-QQQQQQ-dd", "DOT", "SIDEWAYS"))
                    .isInstanceOfSatisfying(BankRecException.class, e -> {
                        assertThat(e.code()).isEqualTo(BankRecErrorCode.VALIDATION_ERROR);
                        assertThat(e.fieldErrors())
                                .containsKeys("encoding", "delimiter", "decimalFormat", "signConvention");
                    });
        }
    }
}
