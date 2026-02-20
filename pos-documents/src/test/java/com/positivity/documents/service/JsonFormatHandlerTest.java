package com.positivity.documents.service;

import com.positivity.documents.internal.exception.RenderingException;
import com.positivity.documents.internal.service.format.JsonFormatHandler;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class JsonFormatHandlerTest {

    private final JsonFormatHandler handler = new JsonFormatHandler(new ObjectMapper());

    @Test
    void shouldConvertJsonToHtml() {
        String html = handler.processContent("{\"name\":\"Alice\",\"age\":30}", new HashMap<>());
        assertTrue(html.contains("JSON Content"));
        assertTrue(html.contains("name"));
        assertTrue(html.contains("Alice"));
    }

    @Test
    void shouldThrowOnMalformedJson() {
        assertThrows(RenderingException.class,
                () -> handler.processContent("{bad json}", new HashMap<>()));
    }
}
