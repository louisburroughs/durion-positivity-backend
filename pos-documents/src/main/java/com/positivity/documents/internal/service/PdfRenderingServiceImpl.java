package com.positivity.documents.internal.service;

import com.itextpdf.html2pdf.HtmlConverter;
import com.positivity.documents.internal.config.PdfConfiguration;
import com.positivity.documents.internal.dto.RenderRequest;
import com.positivity.documents.internal.enums.DocumentFormat;
import com.positivity.documents.internal.exception.RenderingException;
import com.positivity.documents.internal.exception.TemplateNotFoundException;
import com.positivity.documents.internal.exception.UnsupportedFormatException;
import com.positivity.documents.internal.service.format.FormatHandler;
import com.positivity.documents.service.PdfRenderingService;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PdfRenderingServiceImpl implements PdfRenderingService {

    private static final Logger log = LoggerFactory.getLogger(PdfRenderingServiceImpl.class);

    private final Clock clock;
    private final Map<DocumentFormat, FormatHandler> handlerByFormat;
    private final TemplateService templateService;
    private final PdfConfiguration pdfConfiguration;

    public PdfRenderingServiceImpl(
            List<FormatHandler> formatHandlers,
            TemplateService templateService,
            PdfConfiguration pdfConfiguration,
            Clock clock) {
        this.clock = clock;
        this.templateService = templateService;
        this.pdfConfiguration = pdfConfiguration;
        this.handlerByFormat = new EnumMap<>(DocumentFormat.class);
        for (FormatHandler handler : formatHandlers) {
            this.handlerByFormat.put(handler.supportedFormat(), handler);
        }
    }

    @Override
    @NonNull
    public byte[] renderPdf(@NonNull RenderRequest request) {
        if (request.getFormat() == null) {
            throw new UnsupportedFormatException("Document format is required");
        }
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw RenderingException.malformedInput("Document content is required");
        }
        if (request.getContent().length() > pdfConfiguration.maxInputCharacters()) {
            throw RenderingException.malformedInput("Input exceeds maximum allowed size");
        }

        FormatHandler formatHandler = handlerByFormat.get(request.getFormat());
        if (formatHandler == null) {
            throw new UnsupportedFormatException("Unsupported format: " + request.getFormat());
        }

        long startedAt = System.nanoTime();
        try {
            Map<String, Object> templateContext = new HashMap<>();
            templateContext.put("generatedAt", Instant.now(clock).toString());
            templateContext.put("format", request.getFormat().name());
            templateContext.put("title", "Rendered Document");

            String contentHtml = formatHandler.processContent(request.getContent(), templateContext);
            templateContext.put("contentHtml", contentHtml);

            String template = templateService.resolveTemplate(request.getTemplateId());
            String finalHtml = templateService.bindTemplate(template, templateContext);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            HtmlConverter.convertToPdf(finalHtml, outputStream);

            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("Rendered PDF in {} ms for format {}", elapsedMillis, request.getFormat());
            return outputStream.toByteArray();
        } catch (UnsupportedFormatException | TemplateNotFoundException | RenderingException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Document rendering failed for format {}", request.getFormat(), ex);
            throw new RenderingException("PDF rendering failed", ex);
        }
    }
}
