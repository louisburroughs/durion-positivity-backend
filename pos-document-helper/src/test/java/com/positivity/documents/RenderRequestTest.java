package com.positivity.documents;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for RenderRequest DTO.
 *
 * Issue: ADR-0020
 */
class RenderRequestTest {

    @Test
    void builder_AllFieldsSet() {
        // Arrange & Act
        RenderRequest request = RenderRequest.builder()
                .format(DocumentFormat.PDF)
                .templateId("TEST_TEMPLATE")
                .content(Map.of("key", "value"))
                .filename("output.pdf")
                .build();

        // Assert
        assertEquals(DocumentFormat.PDF, request.getFormat());
        assertEquals("TEST_TEMPLATE", request.getTemplateId());
        assertEquals(Map.of("key", "value"), request.getContent());
        assertEquals("output.pdf", request.getFilename());
    }

    @Test
    void pdf_HelperMethod() {
        // Arrange & Act
        RenderRequest request = RenderRequest.pdf("INVOICE_TEMPLATE", Map.of("total", 100))
                .filename("invoice.pdf")
                .build();

        // Assert
        assertEquals(DocumentFormat.PDF, request.getFormat());
        assertEquals("INVOICE_TEMPLATE", request.getTemplateId());
        assertEquals(Map.of("total", 100), request.getContent());
        assertEquals("invoice.pdf", request.getFilename());
    }

    @Test
    void html_HelperMethod() {
        // Arrange & Act
        RenderRequest request =
                RenderRequest.html("REPORT_TEMPLATE", Map.of("rows", 50)).build();

        // Assert
        assertEquals(DocumentFormat.HTML, request.getFormat());
        assertEquals("REPORT_TEMPLATE", request.getTemplateId());
        assertEquals(Map.of("rows", 50), request.getContent());
        assertNull(request.getFilename());
    }

    @Test
    void builder_NullFormat_ThrowsNPE() {
        // Arrange & Act & Assert
        assertThrows(
                NullPointerException.class,
                () -> RenderRequest.builder()
                        .format(null)
                        .templateId("TEST")
                        .content(Map.of())
                        .build());
    }
}
