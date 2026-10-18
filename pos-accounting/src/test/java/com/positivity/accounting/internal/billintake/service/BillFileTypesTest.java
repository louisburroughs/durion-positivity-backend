package com.positivity.accounting.internal.billintake.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BillFileTypes: magic bytes, not names or declared types (S25 #2518)")
class BillFileTypesTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Test
    @DisplayName("PDF, JPEG, PNG and TIFF (both byte orders) are recognised")
    void recognisesTheAllowList() {
        assertThat(BillFileTypes.detect("%PDF-1.7 rest".getBytes(StandardCharsets.US_ASCII)))
                .contains(BillFileTypes.Type.PDF);
        assertThat(BillFileTypes.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10))).contains(BillFileTypes.Type.JPEG);
        assertThat(BillFileTypes.detect(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0)))
                .contains(BillFileTypes.Type.PNG);
        assertThat(BillFileTypes.detect(bytes('I', 'I', 0x2A, 0x00, 8, 0, 0, 0))).contains(BillFileTypes.Type.TIFF);
        assertThat(BillFileTypes.detect(bytes('M', 'M', 0x00, 0x2A, 0, 0, 0, 8))).contains(BillFileTypes.Type.TIFF);
    }

    @Test
    @DisplayName("HEIC is an ISO base media file with a HEIC brand; an MP4 brand is not")
    void recognisesHeicByBrand() {
        assertThat(BillFileTypes.detect(bytes(0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0)))
                .contains(BillFileTypes.Type.HEIC);
        assertThat(BillFileTypes.detect(bytes(0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0)))
                .isEmpty();
    }

    @Test
    @DisplayName("an executable, a script, an empty or a short file is none of them, whatever it is named")
    void refusesEverythingElse() {
        assertThat(BillFileTypes.detect(bytes('M', 'Z', 0x90, 0))).isEmpty();
        assertThat(BillFileTypes.detect("#!/bin/sh\necho hi".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(BillFileTypes.detect(new byte[0])).isEmpty();
        assertThat(BillFileTypes.detect(bytes(0xFF, 0xD8))).isEmpty();
        assertThat(BillFileTypes.detect("date,total\n2026-10-01,10".getBytes(StandardCharsets.US_ASCII))).isEmpty();
    }
}
