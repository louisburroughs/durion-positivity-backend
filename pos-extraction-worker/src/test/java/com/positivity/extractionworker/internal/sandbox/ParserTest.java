package com.positivity.extractionworker.internal.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.extractionworker.TestCorpus;
import com.positivity.extractionworker.internal.enums.DeclaredType;
import com.positivity.extractionworker.internal.enums.ReasonCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The parsers run in process here, through {@link ParseMain#execute}, so the malicious corpus is covered
 * (and measured) without a child JVM per file. {@code ParseSandboxTest} and the API test cover the real
 * child.
 */
class ParserTest {

    @TempDir
    Path dir;

    private SandboxResult inspect(DeclaredType type, byte[] bytes) throws IOException {
        return inspect(type, bytes, TestCorpus.limits());
    }

    private SandboxResult inspect(DeclaredType type, byte[] bytes, SandboxLimits limits) throws IOException {
        Files.write(dir.resolve(ParseMain.INPUT_FILE), bytes);
        return ParseMain.execute(dir, new SandboxRequest(SandboxRequest.INSPECT, type, 0, limits));
    }

    private void assertRejected(SandboxResult result, ReasonCode reason) {
        assertThat(result.outcome()).isEqualTo(SandboxResult.Outcome.REJECTED);
        assertThat(result.reasonCode()).isEqualTo(reason);
    }

    // ---- PDF ---------------------------------------------------------------------------------------

    @Test
    void validThreePagePdfIsAcceptedWithThreePages() throws IOException {
        SandboxResult result = inspect(DeclaredType.PDF, TestCorpus.pdf(3));

        assertThat(result.outcome()).isEqualTo(SandboxResult.Outcome.ACCEPTED);
        assertThat(result.pageCount()).isEqualTo(3);
    }

    @Test
    void thirtyPagePdfIsAcceptedAndThirtyOnePagePdfIsRefusedBeforeRendering() throws IOException {
        assertThat(inspect(DeclaredType.PDF, TestCorpus.pdf(30)).pageCount()).isEqualTo(30);

        assertRejected(inspect(DeclaredType.PDF, TestCorpus.pdf(31)), ReasonCode.PAGE_LIMIT_EXCEEDED);
    }

    @Test
    void encryptedPdfIsRejectedWhetherOrNotTheUserPasswordIsEmpty() throws IOException {
        assertRejected(inspect(DeclaredType.PDF, TestCorpus.encryptedPdf("user-secret")), ReasonCode.ENCRYPTED_PDF);
        assertRejected(inspect(DeclaredType.PDF, TestCorpus.encryptedPdf("")), ReasonCode.ENCRYPTED_PDF);
    }

    @Test
    void everyKindOfActiveContentIsRejected() throws IOException {
        assertRejected(inspect(DeclaredType.PDF, TestCorpus.pdfWithJavaScript()), ReasonCode.PDF_ACTIVE_CONTENT);
        assertRejected(inspect(DeclaredType.PDF, TestCorpus.pdfWithLaunchAction()), ReasonCode.PDF_ACTIVE_CONTENT);
        assertRejected(inspect(DeclaredType.PDF, TestCorpus.pdfWithEmbeddedFiles()), ReasonCode.PDF_ACTIVE_CONTENT);
        assertRejected(
                inspect(DeclaredType.PDF, TestCorpus.pdfWithAnnotationJavaScript()), ReasonCode.PDF_ACTIVE_CONTENT);
    }

    @Test
    void garbageThatPassedTheMagicBytesIsUnreadable() throws IOException {
        assertRejected(inspect(DeclaredType.PDF, "%PDF-1.4 this is not a pdf".getBytes()), ReasonCode.UNREADABLE);
    }

    @Test
    void deeplyNestedPdfEndsInAVerdictAndNeverACrash() throws IOException {
        SandboxResult result = inspect(DeclaredType.PDF, TestCorpus.deeplyNestedPdf(200_000));

        assertThat(result).isNotNull();
        assertThat(result.outcome()).isIn(SandboxResult.Outcome.ACCEPTED, SandboxResult.Outcome.REJECTED);
        if (result.outcome() == SandboxResult.Outcome.REJECTED) {
            assertThat(result.reasonCode()).isEqualTo(ReasonCode.UNREADABLE);
        }
    }

    // ---- images ------------------------------------------------------------------------------------

    @Test
    void validImagesAreAcceptedWithOnePage() throws IOException {
        assertThat(inspect(DeclaredType.PNG, TestCorpus.image("png", 40, 30)).pageCount()).isEqualTo(1);
        assertThat(inspect(DeclaredType.JPEG, TestCorpus.image("jpeg", 40, 30)).pageCount()).isEqualTo(1);
        assertThat(inspect(DeclaredType.TIFF, TestCorpus.tiff(1)).pageCount()).isEqualTo(1);
    }

    @Test
    void imageDeclaringHundredThousandSquarePixelsIsRefusedWithoutDecoding() throws IOException {
        // 10^10 pixels: a decoder that trusted the header would need tens of gigabytes. This returns at once.
        assertRejected(
                inspect(DeclaredType.PNG, TestCorpus.pngDeclaring(100_000, 100_000)),
                ReasonCode.RESOURCE_LIMIT_EXCEEDED);
    }

    @Test
    void tiffWithThirtyOneFramesIsRefusedAndThirtyIsAccepted() throws IOException {
        assertThat(inspect(DeclaredType.TIFF, TestCorpus.tiff(30)).pageCount()).isEqualTo(30);

        assertRejected(inspect(DeclaredType.TIFF, TestCorpus.tiff(31)), ReasonCode.PAGE_LIMIT_EXCEEDED);
    }

    @Test
    void imageThatIsReallyAnotherFormatIsNotTreatedAsTheDeclaredOne() throws IOException {
        assertRejected(inspect(DeclaredType.PNG, TestCorpus.image("jpeg", 8, 8)), ReasonCode.UNREADABLE);
    }

    // ---- CSV ---------------------------------------------------------------------------------------

    @Test
    void csvCountsDataRowsAfterTheHeader() throws IOException {
        SandboxResult result = inspect(DeclaredType.CSV, TestCorpus.csv("a,b,c\n1,2,3\n4,5,6\n\n7,8,9\n"));

        assertThat(result.outcome()).isEqualTo(SandboxResult.Outcome.ACCEPTED);
        assertThat(result.pageCount()).isEqualTo(3);
    }

    @Test
    void csvDelimiterIsSniffedAndBomAndLatin1AreHandled() throws IOException {
        assertThat(inspect(DeclaredType.CSV, TestCorpus.csv("﻿a;b;c\n1;2;3\n")).pageCount())
                .isEqualTo(1);
        assertThat(inspect(DeclaredType.CSV, TestCorpus.csv("a\tb\n1\t2\n3\t4\n")).pageCount())
                .isEqualTo(2);
        byte[] latin1 = "name,city\nJosé,München\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(inspect(DeclaredType.CSV, latin1).pageCount()).isEqualTo(1);
    }

    @Test
    void csvWithNulBytesIsNotText() throws IOException {
        assertRejected(inspect(DeclaredType.CSV, new byte[] {'a', ',', 0, 'b', '\n'}), ReasonCode.TYPE_MISMATCH);
    }

    @Test
    void csvLimitsOnRowsColumnsAndCellLengthAreEnforced() throws IOException {
        SandboxLimits tight = new SandboxLimits(30, 30, 50_000_000L, 25_000_000L, 150, 3, 4, 10, 10_000,
                104_857_600L, 209_715_200L, 0.01, "heif-dec", 20);

        assertRejected(
                inspect(DeclaredType.CSV, TestCorpus.csv("a\n1\n2\n3\n4\n"), tight),
                ReasonCode.RESOURCE_LIMIT_EXCEEDED);
        assertRejected(
                inspect(DeclaredType.CSV, TestCorpus.csv("a,b,c,d,e\n1,2,3,4,5\n"), tight),
                ReasonCode.RESOURCE_LIMIT_EXCEEDED);
        assertRejected(
                inspect(DeclaredType.CSV, TestCorpus.csv("a\n" + "x".repeat(11) + "\n"), tight),
                ReasonCode.RESOURCE_LIMIT_EXCEEDED);
    }

    @Test
    void emptyAndUnterminatedCsvAreUnreadable() throws IOException {
        assertRejected(inspect(DeclaredType.CSV, TestCorpus.csv("\n\n")), ReasonCode.UNREADABLE);
        assertRejected(inspect(DeclaredType.CSV, TestCorpus.csv("a,b\n\"never closed,2\n")), ReasonCode.UNREADABLE);
    }

    // ---- XLSX --------------------------------------------------------------------------------------

    @Test
    void validWorkbookCountsDataRowsOfTheFirstSheet() throws IOException {
        SandboxResult result = inspect(DeclaredType.XLSX, TestCorpus.xlsx(5, 4));

        assertThat(result.outcome()).isEqualTo(SandboxResult.Outcome.ACCEPTED);
        assertThat(result.pageCount()).isEqualTo(5);
    }

    @Test
    void macroEnabledWorkbookIsRejected() throws IOException {
        assertRejected(inspect(DeclaredType.XLSX, TestCorpus.xlsxWithVbaProject()), ReasonCode.MACRO_CONTENT);
    }

    @Test
    void workbookWithAnEntityDeclarationIsRejectedBeforePoiOpensIt() throws IOException {
        assertRejected(inspect(DeclaredType.XLSX, TestCorpus.xlsxWithEntity()), ReasonCode.EXTERNAL_ENTITY);
    }

    @Test
    void zipBombIsRejectedOnTheInflateRatio() throws IOException {
        assertRejected(inspect(DeclaredType.XLSX, TestCorpus.xlsxZipBomb()), ReasonCode.DECOMPRESSION_LIMIT_EXCEEDED);
    }

    @Test
    void zipEntryLimitsAreEnforced() throws IOException {
        SandboxLimits smallEntries = new SandboxLimits(30, 30, 50_000_000L, 25_000_000L, 150, 10_000, 100, 1_000,
                10_000, 1024, 209_715_200L, 0.01, "heif-dec", 20);
        SandboxLimits fewEntries = new SandboxLimits(30, 30, 50_000_000L, 25_000_000L, 150, 10_000, 100, 1_000, 2,
                104_857_600L, 209_715_200L, 0.01, "heif-dec", 20);

        assertRejected(
                inspect(DeclaredType.XLSX, TestCorpus.xlsx(50, 4), smallEntries),
                ReasonCode.DECOMPRESSION_LIMIT_EXCEEDED);
        assertRejected(
                inspect(DeclaredType.XLSX, TestCorpus.xlsx(2, 2), fewEntries),
                ReasonCode.DECOMPRESSION_LIMIT_EXCEEDED);
    }

    @Test
    void spreadsheetRowColumnAndCellLimitsAreEnforced() throws IOException {
        SandboxLimits tight = new SandboxLimits(30, 30, 50_000_000L, 25_000_000L, 150, 5, 3, 20, 10_000,
                104_857_600L, 209_715_200L, 0.01, "heif-dec", 20);

        assertRejected(inspect(DeclaredType.XLSX, TestCorpus.xlsx(10, 2), tight), ReasonCode.RESOURCE_LIMIT_EXCEEDED);
        assertRejected(inspect(DeclaredType.XLSX, TestCorpus.xlsx(2, 5), tight), ReasonCode.RESOURCE_LIMIT_EXCEEDED);
        assertRejected(
                inspect(DeclaredType.XLSX, TestCorpus.xlsxWithLongCell(21), tight),
                ReasonCode.RESOURCE_LIMIT_EXCEEDED);
    }

    @Test
    void notAZipThatPassedTheMagicBytesIsUnreadable() throws IOException {
        assertRejected(inspect(DeclaredType.XLSX, new byte[] {'P', 'K', 3, 4, 1, 2, 3, 4}), ReasonCode.UNREADABLE);
    }
}
