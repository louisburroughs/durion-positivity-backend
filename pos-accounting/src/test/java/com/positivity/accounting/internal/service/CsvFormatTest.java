package com.positivity.accounting.internal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CsvFormat}: RFC 4180 quoting, spreadsheet
 * formula-injection neutralization of text cells (issue #1018), and the
 * guarantee that numeric cells keep their plain leading minus sign.
 */
class CsvFormatTest {

    @Test
    @DisplayName("Text cells starting with =, +, -, or @ are neutralized with a leading single quote")
    void neutralizesFormulaLeadingCharacters() {
        assertEquals("'=2+5", CsvFormat.escapeText("=2+5"));
        assertEquals("'+1234567", CsvFormat.escapeText("+1234567"));
        assertEquals("'-2+3", CsvFormat.escapeText("-2+3"));
        assertEquals("'@SUM(A1:A9)", CsvFormat.escapeText("@SUM(A1:A9)"));
    }

    @Test
    @DisplayName("A leading space-run does not bypass neutralization — spreadsheets trim it on import")
    void neutralizesFormulaBehindLeadingSpaces() {
        assertEquals("' =2+5", CsvFormat.escapeText(" =2+5"));
        assertEquals("'   +1234567", CsvFormat.escapeText("   +1234567"));
        assertEquals("   ", CsvFormat.escapeText("   "));
        assertEquals(" Washington", CsvFormat.escapeText(" Washington"));
    }

    @Test
    @DisplayName("Text cells starting with tab or CR are neutralized per OWASP guidance")
    void neutralizesTabAndCarriageReturn() {
        assertEquals("'\tpadded", CsvFormat.escapeText("\tpadded"));
        assertEquals("\"'\rreturn\"", CsvFormat.escapeText("\rreturn"));
    }

    @Test
    @DisplayName("Ordinary text is unchanged; interior formula characters need no neutralization")
    void leavesOrdinaryTextAlone() {
        assertEquals("Washington", CsvFormat.escapeText("Washington"));
        assertEquals("A=B", CsvFormat.escapeText("A=B"));
        assertEquals("", CsvFormat.escapeText(null));
        assertEquals("", CsvFormat.escapeText(""));
    }

    @Test
    @DisplayName("RFC 4180 quoting still applies after neutralization")
    void quotesAfterNeutralization() {
        assertEquals("\"'=1,2\"", CsvFormat.escapeText("=1,2"));
        assertEquals(
                "\"'=HYPERLINK(\"\"http://evil\"\",\"\"click\"\")\"",
                CsvFormat.escapeText("=HYPERLINK(\"http://evil\",\"click\")"));
    }

    @Test
    @DisplayName("Numeric cells keep their plain leading minus sign — no neutralization")
    void amountKeepsLeadingMinus() {
        assertEquals("-123.45", CsvFormat.amount(new BigDecimal("-123.45")));
        assertEquals("123.45", CsvFormat.amount(new BigDecimal("123.45")));
        assertEquals("", CsvFormat.amount(null));
    }
}
