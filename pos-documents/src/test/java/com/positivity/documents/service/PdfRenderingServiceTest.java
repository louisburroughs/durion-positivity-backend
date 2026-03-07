package com.positivity.documents.service;

import com.positivity.documents.internal.config.PdfConfiguration;
import com.positivity.documents.internal.dto.RenderRequest;
import com.positivity.documents.internal.enums.DocumentFormat;
import com.positivity.documents.internal.exception.RenderingException;
import com.positivity.documents.internal.exception.TemplateNotFoundException;
import com.positivity.documents.internal.service.PdfRenderingServiceImpl;
import com.positivity.documents.internal.service.TemplateService;
import com.positivity.documents.internal.service.format.CsvFormatHandler;
import com.positivity.documents.internal.service.format.JsonFormatHandler;
import com.positivity.documents.internal.service.format.MarkdownFormatHandler;
import com.positivity.documents.internal.service.format.TextFormatHandler;
import com.positivity.documents.internal.service.format.XmlFormatHandler;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfRenderingServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"),
            Clock.systemUTC().getZone());
    private static final String TEMPLATE_BASE_PATH = "classpath:/templates";
    private static final PdfConfiguration PDF_CONFIG = new PdfConfiguration(1_000_000, TEMPLATE_BASE_PATH, 200);

    private final PdfRenderingService service = new PdfRenderingServiceImpl(
            List.of(
                    new JsonFormatHandler(new ObjectMapper()),
                    new XmlFormatHandler(),
                    new MarkdownFormatHandler(),
                    new CsvFormatHandler(PDF_CONFIG),
                    new TextFormatHandler()),
            new TemplateService(new DefaultResourceLoader(), PDF_CONFIG),
            PDF_CONFIG, FIXED_CLOCK);

    @Test
    void shouldRenderPdfUsingDefaultTemplate() {
        RenderRequest request = new RenderRequest();
        request.setFormat(DocumentFormat.JSON);
        request.setContent("{\"hello\":\"world\"}");

        byte[] pdf = service.renderPdf(request);

        assertTrue(pdf.length > 100);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
    }

    @Test
    void shouldRenderUtf8Content() {
        RenderRequest request = new RenderRequest();
        request.setFormat(DocumentFormat.TEXT);
        request.setContent("Olá, München, 東京");

        byte[] pdf = service.renderPdf(request);

        assertTrue(pdf.length > 100);
    }

    @Test
    void shouldThrowWhenTemplateMissing() {
        RenderRequest request = new RenderRequest();
        request.setFormat(DocumentFormat.TEXT);
        request.setTemplateId("MISSING_TEMPLATE");
        request.setContent("test");

        assertThrows(TemplateNotFoundException.class, () -> service.renderPdf(request));
    }

    @Test
    void shouldThrowOnMalformedInput() {
        RenderRequest request = new RenderRequest();
        request.setFormat(DocumentFormat.XML);
        request.setContent("<broken>");

        RenderingException exception = assertThrows(RenderingException.class, () -> service.renderPdf(request));
        assertTrue(exception.isMalformedInput());
    }
}
