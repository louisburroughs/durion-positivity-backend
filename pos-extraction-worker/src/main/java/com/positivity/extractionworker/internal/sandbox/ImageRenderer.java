package com.positivity.extractionworker.internal.sandbox;

import com.positivity.extractionworker.internal.enums.DeclaredType;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Re-encodes one JPEG, PNG or TIFF frame as PNG. The raster alone is written, so EXIF, XMP and every
 * other metadata block is dropped. A frame over the render pixel cap is scaled down to fit.
 */
final class ImageRenderer {

    private ImageRenderer() {}

    static SandboxResult render(Path input, DeclaredType type, int page, SandboxLimits limits, Path output)
            throws IOException {
        ImageIO.setUseCache(false);
        try (ImageInputStream stream = ImageIO.createImageInputStream(input.toFile())) {
            ImageReader reader = ImageInspector.readerFor(stream, type);
            if (reader == null) {
                return SandboxResult.notRenderable();
            }
            try {
                reader.setInput(stream, false, true);
                int frames = type == DeclaredType.TIFF ? reader.getNumImages(true) : 1;
                if (page < 1 || page > frames || frames > limits.maxTiffFrames()) {
                    return SandboxResult.notRenderable();
                }
                long pixels = ImageInspector.pixels(reader, page - 1);
                if (pixels > limits.maxImagePixels()) {
                    return SandboxResult.notRenderable();
                }
                BufferedImage image = reader.read(page - 1);
                BufferedImage png = fit(image, limits.maxRenderPixels());
                if (!ImageIO.write(png, "png", output.toFile())) {
                    return SandboxResult.notRenderable();
                }
                return SandboxResult.rendered(page);
            } finally {
                reader.dispose();
            }
        }
    }

    /** Scales to the pixel cap when over it and converts to a type the PNG writer always accepts. */
    private static BufferedImage fit(BufferedImage source, long maxPixels) {
        long pixels = (long) source.getWidth() * source.getHeight();
        boolean scale = pixels > maxPixels;
        boolean plainType = source.getType() == BufferedImage.TYPE_INT_RGB
                || source.getType() == BufferedImage.TYPE_INT_ARGB
                || source.getType() == BufferedImage.TYPE_3BYTE_BGR
                || source.getType() == BufferedImage.TYPE_4BYTE_ABGR
                || source.getType() == BufferedImage.TYPE_BYTE_GRAY;
        if (!scale && plainType) {
            return source;
        }
        double factor = scale ? Math.sqrt((double) maxPixels / pixels) : 1.0;
        int width = Math.max(1, (int) Math.floor(source.getWidth() * factor));
        int height = Math.max(1, (int) Math.floor(source.getHeight() * factor));
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(width, height, type);
        var graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }
}
