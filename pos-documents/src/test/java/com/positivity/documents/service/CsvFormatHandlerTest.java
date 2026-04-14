package com.positivity.documents.service;

import static org.junit.jupiter.api.Assertions.*;

import com.positivity.documents.internal.config.PdfConfiguration;
import com.positivity.documents.internal.exception.RenderingException;
import com.positivity.documents.internal.service.format.CsvFormatHandler;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class CsvFormatHandlerTest {

    private final CsvFormatHandler handler =
            new CsvFormatHandler(new PdfConfiguration(1_000_000, "classpath:/templates", 200));

    @Test
    void shouldConvertCsvToHtmlTable() {
        String csv = "id,name\n1,Alice\n2,Bob";
        String html = handler.processContent(csv, new HashMap<>());
        assertTrue(html.contains("CSV Content"));
        assertTrue(html.contains("<th>id</th>"));
        assertTrue(html.contains("Alice"));
    }

    @Test
    void shouldFailOnEmptyCsv() {
        var context = new HashMap<String, Object>();
        assertThrows(RenderingException.class, () -> handler.processContent("", context));
    }
}
