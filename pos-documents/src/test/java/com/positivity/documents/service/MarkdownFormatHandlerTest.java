package com.positivity.documents.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.positivity.documents.internal.service.format.MarkdownFormatHandler;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class MarkdownFormatHandlerTest {

    private final MarkdownFormatHandler handler = new MarkdownFormatHandler();

    @Test
    void shouldConvertMarkdownToHtml() {
        String markdown = "# Title\n\n- one\n- two";
        String html = handler.processContent(markdown, new HashMap<>());
        assertTrue(html.contains("<h1>Title</h1>"));
        assertTrue(html.contains("<li>one</li>"));
    }
}
