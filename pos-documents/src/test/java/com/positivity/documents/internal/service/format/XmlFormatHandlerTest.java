package com.positivity.documents.internal.service.format;

import com.positivity.documents.internal.exception.RenderingException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class XmlFormatHandlerTest {

    private final XmlFormatHandler handler = new XmlFormatHandler();

    @Test
    void shouldConvertXmlToHtml() {
        String xml = "<invoice><id>123</id><customer>José</customer></invoice>";
        String html = handler.processContent(xml, new HashMap<>());
        assertTrue(html.contains("XML Content"));
        assertTrue(html.contains("id"));
        assertTrue(html.contains("123"));
    }

    @Test
    void shouldThrowOnMalformedXml() {
        assertThrows(RenderingException.class,
                () -> handler.processContent("<invoice><id>123</invoice>", new HashMap<>()));
    }
}
