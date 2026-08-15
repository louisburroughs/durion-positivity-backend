package com.positivity.supplier.internal.adapter.ediwheelc1;

import com.positivity.supplier.internal.adapter.ediwheelxml.EdiwheelXmlException;
import com.positivity.supplier.internal.adapter.ediwheelxml.EdiwheelXmlSupport;
import jakarta.xml.bind.JAXBContext;
import org.jspecify.annotations.NonNull;

/**
 * The C1 family's view of the shared EDIWheel XML plumbing
 * ({@link EdiwheelXmlSupport} — one JAXB context per document type, qualified output, a
 * namespace-normalising reader that processes no external entities).
 *
 * <p>This class exists to translate, not to parse. The shared support throws
 * {@link EdiwheelXmlException}; C1 callers expect {@link OrderEncodeException} and
 * {@link OrderDecodeException}, because for an order document an unreadable body is a
 * transmission ambiguity (ADR-0052 §3) rather than a generic parse failure — and that meaning
 * belongs to the family, not to the parser.
 */
final class C1XmlSupport {

    /** Namespace of the EDIWheel C1 norms. */
    static final String NAMESPACE = EdiwheelXmlSupport.NAMESPACE;

    private C1XmlSupport() {}

    /**
     * Builds a JAXB context for one root type.
     *
     * @throws IllegalStateException when the type is not bindable — a code defect, surfaced at
     *     startup rather than on the first vendor call
     */
    @NonNull
    static JAXBContext contextFor(@NonNull Class<?> rootType) {
        return EdiwheelXmlSupport.contextFor(rootType);
    }

    /** Serialises a root document: XML declaration, fully qualified. */
    @NonNull
    static String marshal(@NonNull JAXBContext context, @NonNull Object document) {
        try {
            return EdiwheelXmlSupport.marshal(context, document);
        } catch (EdiwheelXmlException e) {
            throw new OrderEncodeException("Unable to serialise an EDIWheel C1 document", e);
        }
    }

    /**
     * Parses a vendor document into {@code rootType}, normalising element namespaces first.
     *
     * @throws OrderDecodeException when the body is not parseable as that document
     */
    @NonNull
    static <T> T unmarshal(@NonNull JAXBContext context, @NonNull Class<T> rootType, @NonNull String body) {
        try {
            return EdiwheelXmlSupport.unmarshal(context, rootType, body);
        } catch (EdiwheelXmlException e) {
            throw new OrderDecodeException(
                    "Response body is not a readable EDIWheel C1 " + rootType.getSimpleName(), e);
        }
    }
}
