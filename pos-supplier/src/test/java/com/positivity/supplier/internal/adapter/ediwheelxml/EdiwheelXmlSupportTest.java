package com.positivity.supplier.internal.adapter.ediwheelxml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The JAXB plumbing shared by every EDIWheel XML family: one context per document type, a
 * qualified writer, and a namespace-normalising, externally-inert reader.
 *
 * <p>Uses a small fixture root type local to this test rather than a package-private family wire
 * class (ADR-0051 §2 keeps those {@code final}/package-private to their own adapter), which also
 * keeps these tests about the shared plumbing rather than any one family's document shape.
 */
@DisplayName("EdiwheelXmlSupport — shared JAXB plumbing for the EDIWheel adapters")
class EdiwheelXmlSupportTest {

    // Every field is explicitly qualified into EdiwheelXmlSupport.NAMESPACE: JAXB binds an
    // unqualified fixture to no namespace by default, but the reader always rewrites incoming
    // elements INTO that namespace (that is the behaviour under test), so the fixture must expect
    // to be found there too -- exactly like every real family wire type, which gets this from its
    // package-info's @XmlSchema instead of a per-annotation namespace.
    @XmlRootElement(name = "widget", namespace = EdiwheelXmlSupport.NAMESPACE)
    @XmlAccessorType(XmlAccessType.FIELD)
    static class Widget {
        @XmlElement(name = "name", namespace = EdiwheelXmlSupport.NAMESPACE)
        String name;

        @XmlElement(name = "quantity", namespace = EdiwheelXmlSupport.NAMESPACE)
        int quantity;

        // JAXB requires a no-arg constructor to unmarshal into.
        Widget() {}

        Widget(String name, int quantity) {
            this.name = name;
            this.quantity = quantity;
        }
    }

    @XmlRootElement(name = "gadget", namespace = EdiwheelXmlSupport.NAMESPACE)
    @XmlAccessorType(XmlAccessType.FIELD)
    static class Gadget {
        @XmlElement(name = "name", namespace = EdiwheelXmlSupport.NAMESPACE)
        String name;
    }

    private final JAXBContext widgetContext = EdiwheelXmlSupport.contextFor(Widget.class);

    @Test
    @DisplayName("contextFor builds a usable JAXB context for a bindable type")
    void contextForBuildsAUsableContext() {
        assertThat(EdiwheelXmlSupport.contextFor(Widget.class)).isNotNull();
    }

    @Test
    @DisplayName("contextFor wraps an unbindable type in IllegalStateException, at startup not first use")
    void contextForWrapsAnUnbindableTypeFailure() {
        assertThatThrownBy(() -> EdiwheelXmlSupport.contextFor(java.io.Closeable.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Closeable");
    }

    @Test
    @DisplayName("marshal emits a fully qualified document under the EDIWheel namespace")
    void marshalEmitsAQualifiedDocument() {
        String xml = EdiwheelXmlSupport.marshal(widgetContext, new Widget("tyre", 4));

        assertThat(xml).contains("<?xml");
        assertThat(xml).contains(EdiwheelXmlSupport.NAMESPACE);
        assertThat(xml).contains("tyre");
        assertThat(xml).contains("<quantity>4</quantity>");
    }

    @Test
    @DisplayName("unmarshal round-trips a document this class itself produced")
    void unmarshalRoundTripsOwnOutput() {
        String xml = EdiwheelXmlSupport.marshal(widgetContext, new Widget("brake pad", 12));

        Widget parsed = EdiwheelXmlSupport.unmarshal(widgetContext, Widget.class, xml);

        assertThat(parsed.name).isEqualTo("brake pad");
        assertThat(parsed.quantity).isEqualTo(12);
    }

    @Test
    @DisplayName("unmarshal normalises an unqualified document into the EDIWheel namespace before binding")
    void unmarshalNormalisesAnUnqualifiedDocument() {
        String unqualified = "<widget><name>filter</name><quantity>3</quantity></widget>";

        Widget parsed = EdiwheelXmlSupport.unmarshal(widgetContext, Widget.class, unqualified);

        assertThat(parsed.name).isEqualTo("filter");
        assertThat(parsed.quantity).isEqualTo(3);
    }

    @Test
    @DisplayName("unmarshal normalises a document qualified under a vendor's own, unrelated namespace")
    void unmarshalNormalisesAVendorNamespacedDocument() {
        String vendorQualified =
                "<widget xmlns=\"urn:some-vendor-namespace\">" + "<name>gasket</name><quantity>7</quantity></widget>";

        Widget parsed = EdiwheelXmlSupport.unmarshal(widgetContext, Widget.class, vendorQualified);

        assertThat(parsed.name).isEqualTo("gasket");
        assertThat(parsed.quantity).isEqualTo(7);
    }

    @Test
    @DisplayName("unmarshal rejects an unrelated root element instead of silently binding an empty instance")
    void unmarshalRejectsAnUnexpectedRootElement() {
        String xml = EdiwheelXmlSupport.marshal(EdiwheelXmlSupport.contextFor(Gadget.class), new Gadget());

        // Whether the unrecognised root fails inside JAXB itself or comes back as some other type
        // that the isInstance check then rejects is a JAXB-implementation detail; either way it
        // must not silently bind an empty Widget with no error code (see the method Javadoc).
        assertThatThrownBy(() -> EdiwheelXmlSupport.unmarshal(widgetContext, Widget.class, xml))
                .isInstanceOf(EdiwheelXmlException.class)
                .hasMessageContaining("Widget");
    }

    @Test
    @DisplayName("unmarshal reports unparseable input as an EdiwheelXmlException, not a raw SAX/JAXB error")
    void unmarshalReportsUnparseableBodyAsATypedException() {
        assertThatThrownBy(() -> EdiwheelXmlSupport.unmarshal(widgetContext, Widget.class, "not xml at all"))
                .isInstanceOf(EdiwheelXmlException.class)
                .hasMessageContaining("Widget");
    }

    @Test
    @DisplayName("unmarshal refuses external entity expansion, closing the file-read/SSRF vector")
    void unmarshalRefusesExternalEntities() {
        String withExternalEntity = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE widget [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<widget><name>&xxe;</name><quantity>1</quantity></widget>";

        // DOCTYPE declarations are disallowed outright, so this fails closed as unparseable
        // rather than silently reading a local file into the response.
        assertThatThrownBy(() -> EdiwheelXmlSupport.unmarshal(widgetContext, Widget.class, withExternalEntity))
                .isInstanceOf(EdiwheelXmlException.class);
    }
}
