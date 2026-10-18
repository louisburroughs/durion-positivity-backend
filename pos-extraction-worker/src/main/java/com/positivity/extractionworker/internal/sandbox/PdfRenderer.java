package com.positivity.extractionworker.internal.sandbox;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Renders one PDF page to PNG. The resolution is lowered when the page at the configured DPI would pass
 * the pixel cap, so a poster-sized MediaBox costs a smaller image, not the heap. Metadata is never
 * copied: the PNG is written from the raster alone.
 */
final class PdfRenderer {

    private static final float MIN_DPI = 20f;

    private PdfRenderer() {}

    static SandboxResult render(Path input, int page, SandboxLimits limits, Path output) throws IOException {
        try (PDDocument document = PdfInspector.load(input, limits.maxStreamCacheBytes())) {
            if (document.isEncrypted()) {
                return SandboxResult.notRenderable();
            }
            int pages = PdfInspector.countPages(document, limits.maxPdfPages());
            if (pages == 0 || pages > limits.maxPdfPages() || page > pages) {
                return SandboxResult.notRenderable();
            }
            PDPage pdPage = document.getPage(page - 1);
            PDRectangle box = pdPage.getCropBox();
            float dpi = fitDpi(box, limits);
            if (box.getWidth() / 72.0 * dpi * (box.getHeight() / 72.0 * dpi) > limits.maxRenderPixels() * 1.05) {
                return SandboxResult.notRenderable();
            }
            PDFRenderer renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            BufferedImage image = renderer.renderImageWithDPI(page - 1, dpi, ImageType.RGB);
            if (!ImageIO.write(image, "png", output.toFile())) {
                return SandboxResult.notRenderable();
            }
            return SandboxResult.rendered(page);
        }
    }

    /** The configured DPI, lowered so width x height at that DPI stays within the pixel cap. */
    static float fitDpi(PDRectangle box, SandboxLimits limits) {
        float dpi = limits.renderDpi();
        double widthPx = box.getWidth() / 72.0 * dpi;
        double heightPx = box.getHeight() / 72.0 * dpi;
        double pixels = Math.max(widthPx, 1) * Math.max(heightPx, 1);
        if (pixels > limits.maxRenderPixels()) {
            dpi = (float) (dpi * Math.sqrt(limits.maxRenderPixels() / pixels));
        }
        return Math.max(dpi, MIN_DPI);
    }
}
