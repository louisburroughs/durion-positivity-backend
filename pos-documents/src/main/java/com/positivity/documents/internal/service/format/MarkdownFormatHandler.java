package com.positivity.documents.internal.service.format;

import com.positivity.documents.internal.enums.DocumentFormat;
import com.positivity.documents.internal.exception.RenderingException;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class MarkdownFormatHandler implements FormatHandler {

    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer htmlRenderer = HtmlRenderer.builder().build();

    @Override
    public String processContent(String rawContent, Map<String, Object> context) {
        try {
            Node node = parser.parse(rawContent);
            String html = htmlRenderer.render(node);
            context.put("markdownLength", rawContent.length());
            return "<section><h2>Markdown Content</h2>" + html + "</section>";
        } catch (Exception ex) {
            throw new RenderingException("Failed to parse Markdown content", ex);
        }
    }

    @Override
    public DocumentFormat supportedFormat() {
        return DocumentFormat.MARKDOWN;
    }
}
