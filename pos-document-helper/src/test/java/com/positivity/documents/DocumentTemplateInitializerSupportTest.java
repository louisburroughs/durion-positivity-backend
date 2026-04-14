package com.positivity.documents;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DocumentTemplateInitializerSupport.
 *
 * Issue: ADR-0020
 */
class DocumentTemplateInitializerSupportTest {

    @Test
    void registerTemplates_AllSucceed() {
        // Arrange
        DocumentTemplateInitializerSupport support = new DocumentTemplateInitializerSupport("test-service");
        AtomicInteger callCount = new AtomicInteger(0);

        List<TemplateRegistration> templates = List.of(
                TemplateRegistration.builder()
                        .templateId("T1")
                        .description("Template 1")
                        .content("test1")
                        .build(),
                TemplateRegistration.builder()
                        .templateId("T2")
                        .description("Template 2")
                        .content("test2")
                        .build(),
                TemplateRegistration.builder()
                        .templateId("T3")
                        .description("Template 3")
                        .content("test3")
                        .build());

        // Act
        int successCount = support.registerTemplates(templates, reg -> callCount.incrementAndGet());

        // Assert
        assertEquals(3, successCount);
        assertEquals(3, callCount.get());
    }

    @Test
    void registerTemplates_SomeFailures() {
        // Arrange
        DocumentTemplateInitializerSupport support = new DocumentTemplateInitializerSupport("test-service");
        AtomicInteger callCount = new AtomicInteger(0);

        List<TemplateRegistration> templates = List.of(
                TemplateRegistration.builder()
                        .templateId("T1")
                        .description("Template 1")
                        .content("test1")
                        .build(),
                TemplateRegistration.builder()
                        .templateId("T2")
                        .description("Template 2")
                        .content("test2")
                        .build(),
                TemplateRegistration.builder()
                        .templateId("T3")
                        .description("Template 3")
                        .content("test3")
                        .build());

        // Fail on second template
        // Act
        int successCount = support.registerTemplates(templates, reg -> {
            callCount.incrementAndGet();
            if (reg.getTemplateId().equals("T2")) {
                throw new RuntimeException("Simulated failure");
            }
        });

        // Assert
        assertEquals(2, successCount); // T1 and T3 succeed
        assertEquals(3, callCount.get()); // All 3 attempted
    }

    @Test
    void registerTemplates_EmptyList() {
        // Arrange
        DocumentTemplateInitializerSupport support = new DocumentTemplateInitializerSupport("test-service");
        AtomicInteger callCount = new AtomicInteger(0);

        // Act
        int successCount = support.registerTemplates(List.of(), reg -> callCount.incrementAndGet());

        // Assert
        assertEquals(0, successCount);
        assertEquals(0, callCount.get());
    }

    @Test
    void registerTemplates_NullTemplates_ThrowsNPE() {
        // Arrange
        DocumentTemplateInitializerSupport support = new DocumentTemplateInitializerSupport("test-service");

        // Act & Assert
        assertThrows(NullPointerException.class, () -> support.registerTemplates(null, reg -> {}));
    }

    @Test
    void registerTemplates_NullFunction_ThrowsNPE() {
        // Arrange
        DocumentTemplateInitializerSupport support = new DocumentTemplateInitializerSupport("test-service");
        List<TemplateRegistration> templates = List.of(TemplateRegistration.builder()
                .templateId("T1")
                .description("test")
                .content("test")
                .build());

        // Act & Assert
        assertThrows(NullPointerException.class, () -> support.registerTemplates(templates, null));
    }
}
