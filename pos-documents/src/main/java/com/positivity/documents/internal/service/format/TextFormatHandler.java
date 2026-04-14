package com.positivity.documents.internal.service.format;

import com.positivity.documents.internal.enums.DocumentFormat;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
public class TextFormatHandler implements FormatHandler {

    @Override
    public String processContent(String rawContent, Map<String, Object> context) {
        StringBuilder builder = new StringBuilder("<section><h2>Text Content</h2>");
        String[] paragraphs = rawContent.split("\\R\\R+");
        for (String paragraph : paragraphs) {
            String escaped = HtmlUtils.htmlEscape(paragraph).replace("\n", "<br />");
            builder.append("<p>").append(escaped).append("</p>");
        }
        builder.append("</section>");
        context.put("paragraphCount", paragraphs.length);
        return builder.toString();
    }

    @Override
    public DocumentFormat supportedFormat() {
        return DocumentFormat.TEXT;
    }
}
