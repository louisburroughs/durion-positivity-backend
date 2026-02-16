package com.positivity.documents.internal.service.format;

import com.positivity.documents.internal.config.PdfConfiguration;
import com.positivity.documents.internal.exception.RenderingException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

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
        assertThrows(RenderingException.class,
                () -> handler.processContent("", new HashMap<>()));
    }
}
