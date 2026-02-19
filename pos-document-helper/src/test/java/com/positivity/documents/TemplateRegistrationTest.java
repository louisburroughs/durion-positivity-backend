package com.positivity.documents;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TemplateRegistration DTO.
 *
 * Issue: ADR-0020
 */
class TemplateRegistrationTest {

    @Test
    void builder_AllFieldsSet() {
        // Arrange & Act
        TemplateRegistration registration = TemplateRegistration.builder()
                .templateId("TEST_TEMPLATE")
                .description("Test template")
                .content("<html>Test</html>")
                .version("2.0")
                .active(true)
                .contentType("text/html")
                .build();

        // Assert
        assertEquals("TEST_TEMPLATE", registration.getTemplateId());
        assertEquals("Test template", registration.getDescription());
        assertEquals("<html>Test</html>", registration.getContent());
        assertEquals("2.0", registration.getVersion());
        assertTrue(registration.isActive());
        assertEquals("text/html", registration.getContentType());
    }

    @Test
    void builder_DefaultValues() {
        // Arrange & Act
        TemplateRegistration registration = TemplateRegistration.builder()
                .templateId("TEST_TEMPLATE")
                .description("Test template")
                .content("<html>Test</html>")
                .build();

        // Assert
        assertEquals("1.0", registration.getVersion());
        assertTrue(registration.isActive());
        assertEquals("text/html", registration.getContentType());
    }

    @Test
    void htmlTemplate_HelperMethod() {
        // Arrange & Act
        TemplateRegistration registration = TemplateRegistration
                .htmlTemplate("INVOICE_TEMPLATE", "Invoice template", "<html>Invoice</html>")
                .version("1.5")
                .build();

        // Assert
        assertEquals("INVOICE_TEMPLATE", registration.getTemplateId());
        assertEquals("Invoice template", registration.getDescription());
        assertEquals("<html>Invoice</html>", registration.getContent());
        assertEquals("text/html", registration.getContentType());
        assertEquals("1.5", registration.getVersion());
    }
}
