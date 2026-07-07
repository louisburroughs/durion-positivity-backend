package com.positivity.bulkloader.internal.parser;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Structure-discovering parser for XML files. The document is converted into a generic map/list
 * tree (repeated sibling elements become lists, attributes become fields) and the record list is
 * discovered the same way as for JSON/YAML.
 */
@Component
public class XmlRecordFileParser implements RecordFileParser {

    @Override
    public boolean supports(@NonNull String fileName) {
        return "xml".equals(FileNames.extension(fileName));
    }

    @Override
    @NonNull
    public RecordSource open(@NonNull InputStream content, @NonNull String fileName) {
        try (content) {
            Document document =
                    secureDocumentBuilderFactory().newDocumentBuilder().parse(content);
            Object tree = toTree(document.getDocumentElement());
            return StructuredRecords.toRecordSource(tree, fileName);
        } catch (ParserConfigurationException | SAXException e) {
            throw new RecordFileParseException("Failed to parse XML file: " + fileName, e);
        } catch (IOException e) {
            throw new RecordFileParseException("Failed to read XML file: " + fileName, e);
        }
    }

    private DocumentBuilderFactory secureDocumentBuilderFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    /** Converts an element into a scalar (text-only element) or a map of attributes and children. */
    private Object toTree(Element element) {
        List<Element> children = childElements(element);
        if (children.isEmpty() && element.getAttributes().getLength() == 0) {
            return element.getTextContent().trim();
        }

        Map<String, Object> node = new LinkedHashMap<>();
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            Node attribute = element.getAttributes().item(i);
            node.put(attribute.getNodeName(), attribute.getNodeValue());
        }
        if (children.isEmpty()) {
            node.put("value", element.getTextContent().trim());
            return node;
        }

        Map<String, List<Element>> byTag = new LinkedHashMap<>();
        for (Element child : children) {
            byTag.computeIfAbsent(child.getTagName(), tag -> new ArrayList<>()).add(child);
        }
        for (Map.Entry<String, List<Element>> entry : byTag.entrySet()) {
            if (entry.getValue().size() == 1) {
                node.put(entry.getKey(), toTree(entry.getValue().getFirst()));
            } else {
                List<Object> values = new ArrayList<>(entry.getValue().size());
                for (Element repeated : entry.getValue()) {
                    values.add(toTree(repeated));
                }
                node.put(entry.getKey(), values);
            }
        }
        return node;
    }

    private List<Element> childElements(Element element) {
        NodeList childNodes = element.getChildNodes();
        List<Element> children = new ArrayList<>();
        for (int i = 0; i < childNodes.getLength(); i++) {
            if (childNodes.item(i) instanceof Element child) {
                children.add(child);
            }
        }
        return children;
    }
}
