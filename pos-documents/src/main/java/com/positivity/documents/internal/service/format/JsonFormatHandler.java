package com.positivity.documents.internal.service.format;

import com.positivity.documents.internal.enums.DocumentFormat;
import com.positivity.documents.internal.exception.RenderingException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class JsonFormatHandler implements FormatHandler {

    private final ObjectMapper objectMapper;

    public JsonFormatHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String processContent(String rawContent, Map<String, Object> context) {
        try {
            Map<String, Object> parsed =
                    objectMapper.readValue(rawContent, new TypeReference<Map<String, Object>>() {});
            context.put("model", parsed);
            return toHtml(parsed);
        } catch (Exception ex) {
            throw RenderingException.malformedInput("Malformed JSON payload", ex);
        }
    }

    @Override
    public DocumentFormat supportedFormat() {
        return DocumentFormat.JSON;
    }

    private String toHtml(Map<String, Object> data) {
        StringBuilder builder = new StringBuilder();
        builder.append("<section><h2>JSON Content</h2>");
        builder.append("<table class=\"kv-table\"><thead><tr><th>Key</th><th>Value</th></tr></thead><tbody>");
        flattenMap("", data)
                .forEach(entry -> builder.append("<tr><td>")
                        .append(HtmlUtils.htmlEscape(entry.getKey()))
                        .append("</td><td>")
                        .append(HtmlUtils.htmlEscape(String.valueOf(entry.getValue())))
                        .append("</td></tr>"));
        builder.append("</tbody></table></section>");
        return builder.toString();
    }

    private List<Map.Entry<String, Object>> flattenMap(String prefix, Map<String, Object> map) {
        List<Map.Entry<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isBlank() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nestedMap) {
                Map<String, Object> typedMap = new LinkedHashMap<>();
                nestedMap.forEach((nestedKey, nestedValue) -> typedMap.put(String.valueOf(nestedKey), nestedValue));
                result.addAll(flattenMap(key, typedMap));
            } else {
                result.add(Map.entry(key, value));
            }
        }
        return result;
    }
}
