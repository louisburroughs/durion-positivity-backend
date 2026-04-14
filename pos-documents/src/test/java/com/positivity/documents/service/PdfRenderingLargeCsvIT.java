package com.positivity.documents.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.positivity.documents.internal.dto.RenderRequest;
import com.positivity.documents.internal.enums.DocumentFormat;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class PdfRenderingLargeCsvIT {

    @Autowired
    private PdfRenderingService pdfRenderingService;

    @Test
    void shouldRenderTenThousandRowCsvUnderThreeSeconds() {
        StringBuilder csvBuilder = new StringBuilder("id,name,amount\n");
        for (int i = 1; i <= 10_000; i++) {
            csvBuilder
                    .append(i)
                    .append(",Item-")
                    .append(i)
                    .append(",")
                    .append(i * 3)
                    .append("\n");
        }

        RenderRequest request = new RenderRequest();
        request.setFormat(DocumentFormat.CSV);
        request.setContent(csvBuilder.toString());

        // Warm up PDF engine/font initialization so this assertion measures
        // steady-state rendering throughput rather than first-call startup cost.
        pdfRenderingService.renderPdf(request);

        long start = System.nanoTime();
        byte[] pdf = pdfRenderingService.renderPdf(request);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(pdf.length > 1000);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
        assertTrue(elapsedMillis < 3000, "Expected rendering under 3000 ms but took " + elapsedMillis + " ms");
    }
}
