package com.positivity.documents.internal.service.format;

import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TextFormatHandlerTest {

    private final TextFormatHandler handler = new TextFormatHandler();

    @Test
    void shouldConvertTextToParagraphs() {
        String text = "First paragraph\nline\n\nSecond paragraph";
        String html = handler.processContent(text, new HashMap<>());
        assertTrue(html.contains("Text Content"));
        assertTrue(html.contains("<p>First paragraph"));
        assertTrue(html.contains("Second paragraph"));
    }
}
