package com.positivity.image.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code equals}/{@code hashCode}/{@code toString} compare {@code content} by value, not array
 * identity — the default record implementation compares by identity, which SonarCloud flags
 * (java:S6218) and which would make two independently-fetched views of the same image compare
 * unequal.
 */
@DisplayName("ImageContentView — content compared by value, not array identity")
class ImageContentViewTest {

    private static final byte[] BYTES = "png-bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("equal fields with a different array instance are equal, and hash alike")
    void equalContentIsEqualAcrossDistinctArrayInstances() {
        ImageContentView a = new ImageContentView("logo.png", "image/png", BYTES.clone());
        ImageContentView b = new ImageContentView("logo.png", "image/png", BYTES.clone());

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    @DisplayName("an instance is equal to itself")
    void reflexive() {
        ImageContentView view = new ImageContentView("logo.png", "image/png", BYTES.clone());

        assertThat(view).isEqualTo(view);
    }

    @Test
    @DisplayName("not equal to a different type, or to null")
    void notEqualToUnrelatedTypesOrNull() {
        ImageContentView view = new ImageContentView("logo.png", "image/png", BYTES.clone());

        assertThat(view).isNotEqualTo("logo.png").isNotEqualTo(null);
    }

    @Test
    @DisplayName("differs by filename, content type, or content bytes")
    void differsByAnyField() {
        ImageContentView view = new ImageContentView("logo.png", "image/png", BYTES.clone());

        assertThat(view).isNotEqualTo(new ImageContentView("other.png", "image/png", BYTES.clone()));
        assertThat(view).isNotEqualTo(new ImageContentView("logo.png", "image/jpeg", BYTES.clone()));
        assertThat(view)
                .isNotEqualTo(
                        new ImageContentView("logo.png", "image/png", "different".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("toString reports a byte count, never the raw content")
    void toStringReportsByteCount() {
        ImageContentView view = new ImageContentView("logo.png", "image/png", BYTES.clone());

        assertThat(view.toString()).contains("logo.png", "image/png", BYTES.length + " bytes");
    }

    @Test
    @DisplayName("null content is reported as such rather than throwing")
    void toStringHandlesNullContent() {
        ImageContentView view = new ImageContentView("logo.png", "image/png", null);

        assertThat(view.toString()).contains("content=null");
    }
}
