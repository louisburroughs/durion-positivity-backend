package com.positivity.documents.internal.service;

import static org.junit.jupiter.api.Assertions.*;

import com.positivity.documents.internal.exception.RenderingException;
import com.positivity.documents.internal.service.format.JsonFormatHandler;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

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
    void invoiceContentShowsCurrencyBesideTotals() {
        // Shape pos-invoice sends for the invoice-default template (#2318).
        String invoice = "{\"documentType\":\"INVOICE\",\"invoiceNumber\":\"INV-1\",\"currencyCode\":\"CAD\","
                + "\"subtotal\":100.00,\"tax\":8.00,\"adjustments\":0,\"total\":108.00}";

        String html = handler.processContent(invoice, new HashMap<>());

        String currencyRow = "<tr><td>currencyCode</td><td>CAD</td></tr>";
        assertTrue(html.contains(currencyRow + "<tr><td>subtotal</td><td>100.0</td></tr>"), html);
        assertTrue(html.contains("<tr><td>total</td><td>108.0</td></tr>"), html);
    }

    @Test
    void shouldThrowOnMalformedJson() {
        assertThrows(RenderingException.class, () -> handler.processContent("{bad json}", new HashMap<>()));
    }
}
