package com.positivity.extractionworker.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.extractionworker.TestCorpus;
import com.positivity.extractionworker.internal.enums.DeclaredType;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MagicBytesTest {

    private static DeclaredType detect(byte[] bytes) {
        byte[] head = Arrays.copyOf(bytes, Math.min(bytes.length, MagicBytes.HEAD_BYTES));
        return MagicBytes.detect(head, head.length);
    }

    @Test
    void recognisesEachBinaryType() {
        assertThat(detect(TestCorpus.pdf(1))).isEqualTo(DeclaredType.PDF);
        assertThat(detect(TestCorpus.image("png", 4, 4))).isEqualTo(DeclaredType.PNG);
        assertThat(detect(TestCorpus.image("jpeg", 4, 4))).isEqualTo(DeclaredType.JPEG);
        assertThat(detect(TestCorpus.tiff(1))).isEqualTo(DeclaredType.TIFF);
        assertThat(detect(TestCorpus.xlsx(1, 1))).isEqualTo(DeclaredType.XLSX);
    }

    @Test
    void recognisesHeicBrandsAndNothingElseThatLooksLikeFtyp() {
        byte[] heic = new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0};
        byte[] mp4 = new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0};

        assertThat(detect(heic)).isEqualTo(DeclaredType.HEIC);
        assertThat(detect(mp4)).isNull();
    }

    @Test
    void textAndShortInputsMatchNothing() {
        assertThat(detect("a,b,c\n1,2,3\n".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(detect(new byte[0])).isNull();
        assertThat(detect(new byte[] {0x25})).isNull();
    }
}
