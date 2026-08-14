package com.positivity.supplier.internal.adapter.ediwheelc1;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import java.io.StringReader;
import java.io.StringWriter;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;
import org.jspecify.annotations.NonNull;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLFilterImpl;

/**
 * JAXB plumbing shared by the C1 codecs: one context per document type, qualified output, and a
 * deliberately forgiving reader.
 *
 * <h2>Reading is namespace-normalising, writing is not</h2>
 *
 * Outbound documents are emitted exactly as the norm states them — every element qualified in
 * {@code http://www.reifen.net}. Inbound documents are rewritten into that namespace before
 * unmarshalling, whatever namespace (or none) their elements arrived in.
 *
 * <p>The asymmetry is intentional. What we send is a commercial document a vendor may hold us
 * to, so it follows the norm to the letter. What we receive is a document some vendor's stack
 * produced, and in practice the C1 family arrives with children variously qualified, unqualified
 * or under a vendor's own namespace. A strict reader would reject an order confirmation whose
 * content is entirely well formed — leaving a real, accepted purchase order looking to us like a
 * transport failure, which under ADR-0052 §3 is exactly the ambiguity that ends in
 * {@code MANUAL_REVIEW}. Being generous about namespaces on the way in costs nothing and removes
 * a whole class of false ambiguity.
 *
 * <h2>External entities are disabled</h2>
 *
 * These documents come from outside this deployment. The parser is configured to process no
 * external entities and no DTDs at all, so a vendor document cannot make this process read a
 * local file or open a network connection.
 */
final class C1XmlSupport {

    /** Namespace of the EDIWheel C1 norms. */
    static final String NAMESPACE = "http://www.reifen.net";

    private C1XmlSupport() {}

    /**
     * Builds a JAXB context for one root type.
     *
     * @throws IllegalStateException when the type is not bindable — a code defect, surfaced at
     *     startup rather than on the first vendor call
     */
    @NonNull
    static JAXBContext contextFor(@NonNull Class<?> rootType) {
        try {
            return JAXBContext.newInstance(rootType);
        } catch (JAXBException e) {
            throw new IllegalStateException("Unable to build a JAXB context for " + rootType.getName(), e);
        }
    }

    /** Serialises a root document: XML declaration, {@code ew:}-prefixed, fully qualified. */
    @NonNull
    static String marshal(@NonNull JAXBContext context, @NonNull Object document) {
        try {
            Marshaller marshaller = context.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
            StringWriter writer = new StringWriter();
            marshaller.marshal(document, writer);
            return writer.toString();
        } catch (JAXBException e) {
            throw new OrderEncodeException("Unable to serialise an EDIWheel C1 document", e);
        }
    }

    /**
     * Parses a vendor document into {@code rootType}, normalising element namespaces first.
     *
     * <p>Unmarshalling is deliberately <strong>untyped</strong> — {@code unmarshal(source)} rather
     * than {@code unmarshal(source, rootType)}. The typed overload force-binds whatever root
     * element it finds into the requested class, so an unrelated document, or a body that is not a
     * C1 document at all, decodes into an empty instance instead of failing. For an order response
     * that empty instance carries no error code, which reads as <em>the vendor accepted the
     * order</em> — a purchase order silently marked confirmed on the strength of a body nobody
     * could read. The untyped call rejects an unexpected root element, and the instance check below
     * closes the remainder.
     *
     * @throws OrderDecodeException when the body is not parseable as that document
     */
    @NonNull
    static <T> T unmarshal(@NonNull JAXBContext context, @NonNull Class<T> rootType, @NonNull String body) {
        Object parsed;
        try {
            Unmarshaller unmarshaller = context.createUnmarshaller();
            SAXSource source = new SAXSource(namespaceNormalisingReader(), new InputSource(new StringReader(body)));
            parsed = unmarshaller.unmarshal(source);
        } catch (OrderDecodeException e) {
            throw e;
        } catch (Exception e) {
            throw new OrderDecodeException(
                    "Response body is not a readable EDIWheel C1 " + rootType.getSimpleName(), e);
        }
        if (!rootType.isInstance(parsed)) {
            throw new OrderDecodeException("Response body is not an EDIWheel C1 " + rootType.getSimpleName() + " but a "
                    + (parsed == null ? "null document" : parsed.getClass().getSimpleName()));
        }
        return rootType.cast(parsed);
    }

    private static XMLReader namespaceNormalisingReader() {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            XMLFilterImpl filter = new NamespaceNormalisingFilter();
            filter.setParent(factory.newSAXParser().getXMLReader());
            return filter;
        } catch (Exception e) {
            throw new OrderDecodeException("Unable to build a secure XML reader for an EDIWheel C1 document", e);
        }
    }

    /**
     * Rewrites every element into {@link #NAMESPACE}, whatever namespace it arrived in.
     *
     * <p>Attributes are left alone: the C1 norms carry no meaningful namespaced attributes, and
     * rewriting {@code xsi:*} would break rather than help.
     */
    private static final class NamespaceNormalisingFilter extends XMLFilterImpl {

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
            super.startElement(NAMESPACE, localName, localName, atts);
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            super.endElement(NAMESPACE, localName, localName);
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) throws SAXException {
            // Swallowed: the rewritten document declares its own single namespace, and passing the
            // vendor's prefixes through would bind them to URIs no element now uses.
        }

        @Override
        public void endPrefixMapping(String prefix) throws SAXException {
            // See startPrefixMapping.
        }
    }
}
