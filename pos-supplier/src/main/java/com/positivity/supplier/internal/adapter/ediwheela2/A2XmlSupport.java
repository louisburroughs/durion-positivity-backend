package com.positivity.supplier.internal.adapter.ediwheela2;

import com.positivity.supplier.internal.adapter.ediwheelxml.EdiwheelXmlException;
import com.positivity.supplier.internal.adapter.ediwheelxml.EdiwheelXmlSupport;
import jakarta.xml.bind.JAXBContext;
import org.jspecify.annotations.NonNull;

/**
 * The A2 family's view of the shared EDIWheel XML plumbing ({@link EdiwheelXmlSupport}).
 *
 * <p>Translation only. The shared support parses; this class decides what a parse failure
 * <em>means</em> for a stock inquiry, which is not what it means for an order: an unreadable quote
 * is simply an unavailable vendor, whereas an unreadable order response is a transmission
 * ambiguity.
 */
final class A2XmlSupport {

    private A2XmlSupport() {}

    /** Builds a JAXB context for one root type. */
    @NonNull
    static JAXBContext contextFor(@NonNull Class<?> rootType) {
        return EdiwheelXmlSupport.contextFor(rootType);
    }

    /** Serialises an inquiry document: XML declaration, fully qualified. */
    @NonNull
    static String marshal(@NonNull JAXBContext context, @NonNull Object document) {
        try {
            return EdiwheelXmlSupport.marshal(context, document);
        } catch (EdiwheelXmlException e) {
            throw new StockInquiryEncodeException("Unable to serialise an EDIWheel A2.5 document", e);
        }
    }

    /** Parses a vendor quote, normalising element namespaces first. */
    @NonNull
    static <T> T unmarshal(@NonNull JAXBContext context, @NonNull Class<T> rootType, @NonNull String body) {
        try {
            return EdiwheelXmlSupport.unmarshal(context, rootType, body);
        } catch (EdiwheelXmlException e) {
            throw new StockInquiryDecodeException(
                    "Response body is not a readable EDIWheel A2.5 " + rootType.getSimpleName(), e);
        }
    }
}
