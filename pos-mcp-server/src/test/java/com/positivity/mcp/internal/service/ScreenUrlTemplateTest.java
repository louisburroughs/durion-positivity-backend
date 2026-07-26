package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ScreenUrlTemplate — placeholder filling")
class ScreenUrlTemplateTest {

    private static final String TEMPLATE = "/workorders?status={status}&location={locationId}";

    @Test
    @DisplayName("all placeholders filled")
    void allPlaceholdersFilled() {
        String url = ScreenUrlTemplate.fill(TEMPLATE, Map.of("status", "OPEN", "locationId", "LOC1"));
        assertThat(url).isEqualTo("/workorders?status=OPEN&location=LOC1");
    }

    @Test
    @DisplayName("unresolved query param is dropped")
    void unresolvedQueryParamDropped() {
        String url = ScreenUrlTemplate.fill(TEMPLATE, Map.of("status", "OPEN"));
        assertThat(url).isEqualTo("/workorders?status=OPEN");
    }

    @Test
    @DisplayName("all query params unresolved collapses to the base path")
    void allUnresolvedCollapsesToBase() {
        String url = ScreenUrlTemplate.fill(TEMPLATE, Map.of());
        assertThat(url).isEqualTo("/workorders");
    }

    @Test
    @DisplayName("values are URL-encoded")
    void valuesAreUrlEncoded() {
        String url = ScreenUrlTemplate.fill("/invoices?status={status}", Map.of("status", "past due"));
        assertThat(url).isEqualTo("/invoices?status=past+due");
    }

    @Test
    @DisplayName("template without a query string is returned unchanged")
    void noQueryStringReturnedAsIs() {
        String url = ScreenUrlTemplate.fill("/dashboard", Map.of("status", "OPEN"));
        assertThat(url).isEqualTo("/dashboard");
    }
}
