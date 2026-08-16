package com.positivity.supplier.internal.adapter.ediwheelb3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.domain.model.SupplierInvoice;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Reading a vendor's AR documents (CAP-321 #1227).
 *
 * <p>The tests that matter here are about money changing meaning in transit. A credit read as an
 * invoice is a payment made instead of received; an amount silently defaulted to zero is a debt
 * that disappears. Both are the kind of error that is only discovered by somebody reconciling a
 * statement weeks later, so the codec refuses rather than guesses and these tests pin the refusals.
 */
@DisplayName("EdiwheelB33InvoiceCodec — reading vendor AR documents (#1227)")
class EdiwheelB33InvoiceCodecTest {

    private final EdiwheelB33InvoiceCodec codec = new EdiwheelB33InvoiceCodec();

    private static String document(String documentTypeCode, String body) {
        return """
            <invoice_list xmlns="http://www.reifen.net">
              <DocumentID>B3</DocumentID>
              <Variant>3</Variant>
              <invoice>
                <IssueDate>2026-08-14</IssueDate>
                <DocumentNumber>INV-40021</DocumentNumber>
                <DocumentTypeCode>%s</DocumentTypeCode>
                <DocumentCurrency><CurrencyCode>EUR</CurrencyCode></DocumentCurrency>
                %s
              </invoice>
            </invoice_list>
            """.formatted(documentTypeCode, body);
    }

    private static final String ONE_LINE_AND_TOTALS = """
            <LineLevel>
              <LineID>1</LineID>
              <References><OrderReference><ReferenceID>PO-778</ReferenceID></OrderReference></References>
              <LineItemNetAmount><AmountValue>240.00</AmountValue></LineItemNetAmount>
              <Article>
                <ArticleIdentification>
                  <EANUCCArticleID>5012345678900</EANUCCArticleID>
                  <ManufacturersArticleID>MI-2055516</ManufacturersArticleID>
                </ArticleIdentification>
                <InvoicedQuantity><QuantityValue>4</QuantityValue></InvoicedQuantity>
                <PriceDetails><PriceAmount>60.00</PriceAmount></PriceDetails>
              </Article>
            </LineLevel>
            <Summary>
              <TotalAmount><AmountValue>288.00</AmountValue></TotalAmount>
              <TaxableAmount><AmountValue>240.00</AmountValue></TaxableAmount>
              <TaxAmount><AmountValue>48.00</AmountValue></TaxAmount>
            </Summary>
            """;

    @Test
    @DisplayName("an invoice is read with the vendor's own figures, not recomputed ones")
    void amountsAreTranscribed() {
        List<SupplierInvoice> invoices = codec.decode(document("380", ONE_LINE_AND_TOTALS));

        assertThat(invoices).singleElement().satisfies(invoice -> {
            assertThat(invoice.vendorInvoiceNumber()).isEqualTo("INV-40021");
            assertThat(invoice.type()).isEqualTo(SupplierInvoice.Type.INVOICE);
            assertThat(invoice.currency()).isEqualTo("EUR");
            // The vendor's stated total, not 240 + 48 arrived at independently. Where a vendor's
            // arithmetic disagrees with ours, what it will expect to be paid is its figure.
            assertThat(invoice.totalGrossAmount()).isEqualByComparingTo("288.00");
            assertThat(invoice.totalNetAmount()).isEqualByComparingTo("240.00");
            assertThat(invoice.totalTaxAmount()).isEqualByComparingTo("48.00");
            assertThat(invoice.lines()).singleElement().satisfies(line -> {
                assertThat(line.articleEan()).isEqualTo("5012345678900");
                assertThat(line.quantity()).isEqualTo(4);
                assertThat(line.lineAmount()).isEqualByComparingTo("240.00");
                assertThat(line.vendorOrderReference()).isEqualTo("PO-778");
            });
        });
    }

    @ParameterizedTest
    @CsvSource({"380,INVOICE", "84,INVOICE", "381,CREDIT_NOTE", "83,CREDIT_NOTE"})
    @DisplayName("the document type decides which way the money runs")
    void documentTypeDecidesDirection(String code, SupplierInvoice.Type expected) {
        assertThat(codec.decode(document(code, ONE_LINE_AND_TOTALS)).getFirst().type())
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("an unrecognised document type is refused rather than assumed")
    void unknownDocumentTypeIsRefused() {
        // Either direction is possible, and importing a credit as a debt reaches a payment run.
        assertThatThrownBy(() -> codec.decode(document("ZZ9", ONE_LINE_AND_TOTALS)))
                .isInstanceOf(InvoiceDecodeException.class)
                .hasMessageContaining("refusing to guess");
    }

    @Test
    @DisplayName("an invoice with no number is refused: nothing could deduplicate it")
    void missingInvoiceNumberIsRefused() {
        String body = """
            <invoice_list xmlns="http://www.reifen.net">
              <invoice>
                <IssueDate>2026-08-14</IssueDate>
                <DocumentTypeCode>380</DocumentTypeCode>
                <DocumentCurrency><CurrencyCode>EUR</CurrencyCode></DocumentCurrency>
              </invoice>
            </invoice_list>
            """;

        // Overlapping windows re-deliver the same document by design; without a number every
        // re-fetch would import it afresh as a new debt.
        assertThatThrownBy(() -> codec.decode(body))
                .isInstanceOf(InvoiceDecodeException.class)
                .hasMessageContaining("DocumentNumber");
    }

    @Test
    @DisplayName("an amount with no currency is refused: it is not a sum of money")
    void missingCurrencyIsRefused() {
        String body = """
            <invoice_list xmlns="http://www.reifen.net">
              <invoice>
                <IssueDate>2026-08-14</IssueDate>
                <DocumentNumber>INV-1</DocumentNumber>
                <DocumentTypeCode>380</DocumentTypeCode>
              </invoice>
            </invoice_list>
            """;

        // Defaulting to the profile's currency would let a foreign-currency invoice be paid as
        // though it were domestic.
        assertThatThrownBy(() -> codec.decode(body))
                .isInstanceOf(InvoiceDecodeException.class)
                .hasMessageContaining("DocumentCurrency");
    }

    @Test
    @DisplayName("an unreadable amount is absent, not zero")
    void unreadableAmountIsNull() {
        String summary = "<Summary><TotalAmount><AmountValue>n/a</AmountValue></TotalAmount></Summary>";

        SupplierInvoice invoice = codec.decode(document("380", summary)).getFirst();

        // Zero is a figure a vendor might genuinely send. Treating an unreadable amount as one
        // would make a broken invoice indistinguishable from a nil one.
        assertThat(invoice.totalGrossAmount()).isNull();
    }

    @Test
    @DisplayName("a window the vendor refused is an error, not an empty result")
    void documentLevelErrorIsRaised() {
        String body = """
            <invoice_list xmlns="http://www.reifen.net">
              <ErrorHead><ErrorCode>E17</ErrorCode><ErrorText>window too wide</ErrorText></ErrorHead>
            </invoice_list>
            """;

        // Silently returning nothing would advance the checkpoint past invoices never fetched.
        assertThatThrownBy(() -> codec.decode(body))
                .isInstanceOf(InvoiceDecodeException.class)
                .hasMessageContaining("E17");
    }

    @Test
    @DisplayName("a window with no invoices is an ordinary answer")
    void emptyWindowIsNotAFailure() {
        String body = """
            <invoice_list xmlns="http://www.reifen.net">
              <DocumentID>B3</DocumentID><Variant>3</Variant>
            </invoice_list>
            """;

        // Most days a vendor issues nothing.
        assertThat(codec.decode(body)).isEmpty();
    }

    @Test
    @DisplayName("lines are numbered by position when the vendor's own ids are unusable")
    void linesFallBackToPosition() {
        String twoLines = """
                <LineLevel><LineID>abc</LineID>
                  <LineItemNetAmount><AmountValue>10.00</AmountValue></LineItemNetAmount></LineLevel>
                <LineLevel><LineID>def</LineID>
                  <LineItemNetAmount><AmountValue>20.00</AmountValue></LineItemNetAmount></LineLevel>
                """;

        // An invoice with unusable line ids is still an invoice worth importing: the totals are
        // what AP pays on, and position is enough to refer to a line by.
        assertThat(codec.decode(document("380", twoLines)).getFirst().lines())
                .extracting(SupplierInvoice.Line::lineNumber)
                .containsExactly(1, 2);
    }

    @Test
    @DisplayName("comma decimals are read, as every sibling codec already reads them")
    void commaDecimalsAreRead() {
        String summary = "<Summary><TotalAmount><AmountValue>240,00</AmountValue></TotalAmount></Summary>";

        // The norms use a comma separator. Refusing it would record a perfectly good figure as
        // unreadable and land an invoice with no total at all.
        assertThat(codec.decode(document("380", summary)).getFirst().totalGrossAmount())
                .isEqualByComparingTo("240.00");
    }

    @Test
    @DisplayName("a grouped figure is not guessed at")
    void groupedFigureIsAbsent() {
        String summary = "<Summary><TotalAmount><AmountValue>1.234,56</AmountValue></TotalAmount></Summary>";

        // "1.234" is a thousand in one convention and one-and-a-bit in another, and nothing in the
        // document says which. A wrong guess here is off by a factor of a thousand on a payment,
        // so it is recorded as unreadable and somebody looks — the same rule the rest of this
        // codec follows, and the same behaviour every sibling codec has.
        assertThat(codec.decode(document("380", summary)).getFirst().totalGrossAmount())
                .isNull();
    }

    @Test
    @DisplayName("a whole quantity written with decimals is that quantity")
    void wholeQuantityWithDecimalsIsExact() {
        String line = """
                <LineLevel><LineID>1</LineID><Article>
                  <InvoicedQuantity><QuantityValue>12,000</QuantityValue></InvoicedQuantity>
                </Article></LineLevel>
                """;

        assertThat(codec.decode(document("380", line))
                        .getFirst()
                        .lines()
                        .getFirst()
                        .quantity())
                .isEqualTo(12);
    }

    @Test
    @DisplayName("a non-whole quantity is absent, never truncated")
    void fractionalQuantityIsAbsent() {
        String line = """
                <LineLevel><LineID>1</LineID><Article>
                  <InvoicedQuantity><QuantityValue>1,9</QuantityValue></InvoicedQuantity>
                </Article></LineLevel>
                """;

        // Truncating to 1 would restate what the vendor invoiced, and the line would then disagree
        // with its own amount.
        assertThat(codec.decode(document("380", line))
                        .getFirst()
                        .lines()
                        .getFirst()
                        .quantity())
                .isNull();
    }
}
