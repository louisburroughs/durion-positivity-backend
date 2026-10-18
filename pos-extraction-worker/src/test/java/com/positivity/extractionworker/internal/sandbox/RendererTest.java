package com.positivity.extractionworker.internal.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.extractionworker.TestCorpus;
import com.positivity.extractionworker.internal.enums.DeclaredType;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RendererTest {

    @TempDir
    Path dir;

    private SandboxResult render(DeclaredType type, byte[] bytes, int page, SandboxLimits limits) throws IOException {
        Files.write(dir.resolve(ParseMain.INPUT_FILE), bytes);
        return ParseMain.execute(dir, new SandboxRequest(SandboxRequest.RENDER, type, page, limits));
    }

    private BufferedImage page() throws IOException {
        return ImageIO.read(new ByteArrayInputStream(Files.readAllBytes(dir.resolve(SandboxResult.RENDERED_FILE))));
    }

    @Test
    void pdfPagesOneToThreeRenderAndPageFourIsNotRenderable() throws IOException {
        byte[] pdf = TestCorpus.pdf(3);

        for (int page = 1; page <= 3; page++) {
            SandboxResult result = render(DeclaredType.PDF, pdf, page, TestCorpus.limits());
            assertThat(result.outcome()).isEqualTo(SandboxResult.Outcome.RENDERED);
            assertThat(page()).isNotNull();
        }
        assertThat(render(DeclaredType.PDF, pdf, 4, TestCorpus.limits()).outcome())
                .isEqualTo(SandboxResult.Outcome.NOT_RENDERABLE);
        assertThat(render(DeclaredType.PDF, pdf, 0, TestCorpus.limits()).outcome())
                .isEqualTo(SandboxResult.Outcome.NOT_RENDERABLE);
    }

    @Test
    void pdfRendersAtTheConfiguredDpi() throws IOException {
        render(DeclaredType.PDF, TestCorpus.pdf(1), 1, TestCorpus.limits());

        BufferedImage image = page();
        // A4 is 8.27 x 11.69 in; letter, PDFBox's default, is 8.5 x 11 in. Either is about 1275 px wide at 150 dpi.
        assertThat(image.getWidth()).isBetween(1200, 1300);
    }

    @Test
    void pdfRenderingLowersTheDpiToStayWithinThePixelCap() throws IOException {
        SandboxLimits capped = new SandboxLimits(
                30,
                30,
                50_000_000L,
                100_000L,
                150,
                10_000,
                100,
                1_000,
                10_000,
                104_857_600L,
                209_715_200L,
                0.01,
                "heif-dec",
                20);

        render(DeclaredType.PDF, TestCorpus.pdf(1), 1, capped);

        BufferedImage image = page();
        assertThat((long) image.getWidth() * image.getHeight()).isLessThanOrEqualTo(110_000L);
    }

    @Test
    void fitDpiLowersOnlyWhenOverTheCap() {
        PDRectangle letter = PDRectangle.LETTER;

        assertThat(PdfRenderer.fitDpi(letter, TestCorpus.limits())).isEqualTo(150f);
        assertThat(PdfRenderer.fitDpi(new PDRectangle(14400, 14400), TestCorpus.limits()))
                .isLessThan(150f);
    }

    @Test
    void imagesAreReEncodedAsPng() throws IOException {
        assertThat(render(DeclaredType.JPEG, TestCorpus.image("jpeg", 50, 40), 1, TestCorpus.limits())
                        .outcome())
                .isEqualTo(SandboxResult.Outcome.RENDERED);
        BufferedImage fromJpeg = page();
        assertThat(fromJpeg.getWidth()).isEqualTo(50);
        assertThat(Files.readAllBytes(dir.resolve(SandboxResult.RENDERED_FILE))[1])
                .isEqualTo((byte) 'P');

        assertThat(render(DeclaredType.PNG, TestCorpus.image("png", 20, 20), 1, TestCorpus.limits())
                        .outcome())
                .isEqualTo(SandboxResult.Outcome.RENDERED);
        assertThat(render(DeclaredType.PNG, TestCorpus.image("png", 20, 20), 2, TestCorpus.limits())
                        .outcome())
                .isEqualTo(SandboxResult.Outcome.NOT_RENDERABLE);
    }

    @Test
    void tiffFramesRenderIndividually() throws IOException {
        byte[] tiff = TestCorpus.tiff(3);

        assertThat(render(DeclaredType.TIFF, tiff, 3, TestCorpus.limits()).outcome())
                .isEqualTo(SandboxResult.Outcome.RENDERED);
        assertThat(render(DeclaredType.TIFF, tiff, 4, TestCorpus.limits()).outcome())
                .isEqualTo(SandboxResult.Outcome.NOT_RENDERABLE);
    }

    @Test
    void imageOverThePixelCapIsScaledDown() throws IOException {
        SandboxLimits capped = new SandboxLimits(
                30,
                30,
                50_000_000L,
                1_000L,
                150,
                10_000,
                100,
                1_000,
                10_000,
                104_857_600L,
                209_715_200L,
                0.01,
                "heif-dec",
                20);

        render(DeclaredType.PNG, TestCorpus.image("png", 100, 100), 1, capped);

        BufferedImage image = page();
        assertThat((long) image.getWidth() * image.getHeight()).isLessThanOrEqualTo(1_000L);
    }

    @Test
    void spreadsheetsAndEncryptedOrOversizedFilesDoNotRender() throws IOException {
        assertThat(render(DeclaredType.CSV, TestCorpus.csv("a,b\n1,2\n"), 1, TestCorpus.limits())
                        .outcome())
                .isEqualTo(SandboxResult.Outcome.NOT_RENDERABLE);
        assertThat(render(DeclaredType.PDF, TestCorpus.encryptedPdf("pw"), 1, TestCorpus.limits())
                        .outcome())
                .isEqualTo(SandboxResult.Outcome.NOT_RENDERABLE);
        assertThat(render(DeclaredType.PDF, TestCorpus.pdf(31), 1, TestCorpus.limits())
                        .outcome())
                .isEqualTo(SandboxResult.Outcome.NOT_RENDERABLE);
        assertThat(render(DeclaredType.PNG, TestCorpus.pngDeclaring(100_000, 100_000), 1, TestCorpus.limits())
                        .outcome())
                .isEqualTo(SandboxResult.Outcome.NOT_RENDERABLE);
    }
}
