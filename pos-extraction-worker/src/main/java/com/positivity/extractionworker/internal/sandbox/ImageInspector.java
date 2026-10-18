package com.positivity.extractionworker.internal.sandbox;

import com.positivity.extractionworker.internal.enums.DeclaredType;
import com.positivity.extractionworker.internal.enums.ReasonCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * JPEG, PNG and TIFF checks. Dimensions come from the header and are checked before a single pixel is
 * decoded; a TIFF's frames are counted the same way. Only then is page 1 decoded, to prove the file reads.
 */
final class ImageInspector {

    private ImageInspector() {}

    static SandboxResult inspect(Path input, DeclaredType type, SandboxLimits limits) throws IOException {
        ImageIO.setUseCache(false);
        try (ImageInputStream stream = ImageIO.createImageInputStream(input.toFile())) {
            ImageReader reader = readerFor(stream, type);
            if (reader == null) {
                return SandboxResult.rejected(ReasonCode.UNREADABLE);
            }
            try {
                reader.setInput(stream, false, true);
                if (pixels(reader, 0) > limits.maxImagePixels()) {
                    return SandboxResult.rejected(ReasonCode.RESOURCE_LIMIT_EXCEEDED);
                }
                int frames = type == DeclaredType.TIFF ? reader.getNumImages(true) : 1;
                if (frames > limits.maxTiffFrames()) {
                    return SandboxResult.rejected(ReasonCode.PAGE_LIMIT_EXCEEDED);
                }
                if (frames < 1) {
                    return SandboxResult.rejected(ReasonCode.UNREADABLE);
                }
                for (int i = 1; i < frames; i++) {
                    if (pixels(reader, i) > limits.maxImagePixels()) {
                        return SandboxResult.rejected(ReasonCode.RESOURCE_LIMIT_EXCEEDED);
                    }
                }
                if (reader.read(0) == null) {
                    return SandboxResult.rejected(ReasonCode.UNREADABLE);
                }
                return SandboxResult.accepted(frames);
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * The reader ImageIO picks for the stream, if its format is the declared one. A reader for another
     * format means the magic bytes passed for a different type than the one the decoder sees.
     */
    static ImageReader readerFor(ImageInputStream stream, DeclaredType type) {
        if (stream == null) {
            return null;
        }
        Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
        while (readers.hasNext()) {
            ImageReader reader = readers.next();
            try {
                if (formatMatches(reader.getFormatName(), type)) {
                    return reader;
                }
            } catch (IOException e) {
                // try the next reader
            }
            reader.dispose();
        }
        return null;
    }

    private static boolean formatMatches(String formatName, DeclaredType type) {
        String format = formatName.toLowerCase(Locale.ROOT);
        return switch (type) {
            case JPEG -> format.equals("jpeg") || format.equals("jpg");
            case PNG -> format.equals("png");
            case TIFF -> format.equals("tiff") || format.equals("tif");
            default -> false;
        };
    }

    /** Width x height of frame {@code index} from its header. */
    static long pixels(ImageReader reader, int index) throws IOException {
        return (long) reader.getWidth(index) * (long) reader.getHeight(index);
    }
}
