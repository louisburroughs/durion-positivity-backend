package com.positivity.documents.internal.service.format;

import com.positivity.documents.internal.enums.DocumentFormat;
import com.positivity.documents.internal.exception.RenderingException;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.Map;

@Component
public class XmlFormatHandler implements FormatHandler {

    @Override
    public String processContent(String rawContent, Map<String, Object> context) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(rawContent)));
            document.getDocumentElement().normalize();
            context.put("xmlRoot", document.getDocumentElement().getNodeName());

            StringBuilder builder = new StringBuilder("<section><h2>XML Content</h2>");
            builder.append(renderNode(document.getDocumentElement()));
            builder.append("</section>");
            return builder.toString();
        } catch (Exception ex) {
            throw RenderingException.malformedInput("Malformed XML payload", ex);
        }
    }

    @Override
    public DocumentFormat supportedFormat() {
        return DocumentFormat.XML;
    }

    private String renderNode(Node node) {
        StringBuilder builder = new StringBuilder();
        builder.append("<div class=\"xml-node\"><strong>")
                .append(HtmlUtils.htmlEscape(node.getNodeName()))
                .append("</strong>");

        NamedNodeMap attributes = node.getAttributes();
        if (attributes != null && attributes.getLength() > 0) {
            builder.append("<ul>");
            for (int index = 0; index < attributes.getLength(); index++) {
                Node attribute = attributes.item(index);
                builder.append("<li>@")
                        .append(HtmlUtils.htmlEscape(attribute.getNodeName()))
                        .append(" = ")
                        .append(HtmlUtils.htmlEscape(attribute.getNodeValue()))
                        .append("</li>");
            }
            builder.append("</ul>");
        }

        NodeList childNodes = node.getChildNodes();
        boolean hasElementChildren = false;
        for (int index = 0; index < childNodes.getLength(); index++) {
            Node child = childNodes.item(index);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                hasElementChildren = true;
                builder.append(renderNode(child));
            }
        }

        String text = node.getTextContent() == null ? "" : node.getTextContent().trim();
        if (!text.isBlank() && !hasElementChildren) {
            builder.append("<p>").append(HtmlUtils.htmlEscape(text)).append("</p>");
        }

        builder.append("</div>");
        return builder.toString();
    }
}
