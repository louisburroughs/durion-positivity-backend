package com.positivity.image.internal.dto;

import com.positivity.image.internal.exception.ImageValidationException;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Base64;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** An image to store, with the origin it was fetched from where there was one (CAP-324 #1257). */
@Schema(description = "Image bytes to store, base64-encoded, with optional provenance")
public record StoreImageRequest(
        @Schema(description = "Name to store the image under", example = "primacy-4-tread.jpg") @NotBlank
        String filename,

        @Schema(description = "MIME type of the bytes", example = "image/jpeg") @NotBlank
        String contentType,

        @Schema(description = "The image bytes, base64-encoded") @NotNull
        String content,

        @Schema(
                description =
                        "Where the bytes were fetched from. Kept as provenance for audit and reconciliation, never used as a render source")
        @Nullable
        String sourceUri,

        @Schema(description = "Tags this image should carry") @Nullable
        List<String> context) {

    /**
     * The decoded bytes.
     *
     * <p>Decoded here rather than accepted as a byte array so an unreadable body is a request
     * problem with a clear message, rather than a deserialisation failure somewhere in the stack.
     */
    public byte[] decodedContent() {
        try {
            return Base64.getDecoder().decode(content);
        } catch (IllegalArgumentException e) {
            // Catches the JDK's own IllegalArgumentException from Base64.getDecoder().decode and
            // re-types it: a bare IllegalArgumentException reaching ImageExceptionHandler would no
            // longer be treated as a client error (see ImageValidationException).
            throw new ImageValidationException("content is not valid base64: " + e.getMessage(), e);
        }
    }
}
