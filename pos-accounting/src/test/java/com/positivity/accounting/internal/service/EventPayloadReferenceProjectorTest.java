package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.EventPayloadReference;
import com.positivity.accounting.internal.dto.ResolvedDisplayReference;
import com.positivity.accounting.internal.enums.DisplayReferenceType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the accounting-event payload display projection (issues #1778, #1797).
 *
 * <p>The projection's job is to let a screen label the reference values in an event payload
 * without exposing raw identifiers and without reaching across a domain boundary. These tests pin
 * the two halves of that: which values the walk recognizes in a free-form producer payload, and
 * the rule that an unresolvable reference yields nulls rather than the identifier as text.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventPayloadReferenceProjector Tests (issues #1778, #1797)")
class EventPayloadReferenceProjectorTest {

    private static final UUID INVOICE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0001");
    private static final UUID CUSTOMER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0002");
    private static final UUID VENDOR_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0003");
    private static final UUID ORGANIZATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0004");
    private static final UUID LOCATION_UUID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0005");

    @Mock
    private DisplayReferenceResolver displayReferenceResolver;

    @InjectMocks
    private EventPayloadReferenceProjector projector;

    @Test
    @DisplayName("A null or empty payload projects nothing and resolves nothing")
    void emptyPayloadProjectsNothing() {
        assertThat(projector.project(null)).isEmpty();
        assertThat(projector.project(Map.of())).isEmpty();
        verify(displayReferenceResolver, never()).resolve(any(), anyCollection());
        verify(displayReferenceResolver, never()).resolveCodes(any(), anyCollection());
    }

    @Test
    @DisplayName("Recognized keys are projected with the display values accounting can resolve")
    void projectsResolvedDisplayValues() {
        when(displayReferenceResolver.resolve(eq(DisplayReferenceType.INVOICE), anyCollection()))
                .thenReturn(Map.of(INVOICE_ID, ResolvedDisplayReference.ofReference("INV-2026-004417")));
        when(displayReferenceResolver.resolve(eq(DisplayReferenceType.CUSTOMER), anyCollection()))
                .thenReturn(Map.of(CUSTOMER_ID, new ResolvedDisplayReference("Northside Fleet Services", "C-10427")));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invoiceId", INVOICE_ID.toString());
        payload.put("customerId", CUSTOMER_ID.toString());

        List<EventPayloadReference> projection = projector.project(payload);

        assertThat(projection).hasSize(2);
        EventPayloadReference customer = byPath(projection, "customerId");
        assertThat(customer.getReferenceType()).isEqualTo(DisplayReferenceType.CUSTOMER);
        assertThat(customer.getId()).isEqualTo(CUSTOMER_ID);
        // The raw value is the payload string; the id is that same value parsed.
        assertThat(customer.getRawValue()).isEqualTo(CUSTOMER_ID.toString());
        assertThat(customer.getDisplayName()).isEqualTo("Northside Fleet Services");
        assertThat(customer.getDisplayReference()).isEqualTo("C-10427");

        EventPayloadReference invoice = byPath(projection, "invoiceId");
        assertThat(invoice.getReferenceType()).isEqualTo(DisplayReferenceType.INVOICE);
        assertThat(invoice.getDisplayName()).isNull();
        assertThat(invoice.getDisplayReference()).isEqualTo("INV-2026-004417");
    }

    @Test
    @DisplayName("An unresolvable reference is still projected, with null display values — never the UUID")
    void unresolvedReferenceProjectsNullDisplayValues() {
        // The resolver knows nothing: the ORGANIZATION type never resolves today (no directory
        // exists), and the invoice is simply absent from the replica.
        lenient()
                .when(displayReferenceResolver.resolve(any(DisplayReferenceType.class), anyCollection()))
                .thenReturn(Map.of());

        List<EventPayloadReference> projection =
                projector.project(Map.of("organizationId", ORGANIZATION_ID.toString()));

        assertThat(projection).hasSize(1);
        EventPayloadReference organization = projection.getFirst();
        assertThat(organization.getReferenceType()).isEqualTo(DisplayReferenceType.ORGANIZATION);
        assertThat(organization.getId()).isEqualTo(ORGANIZATION_ID);
        assertThat(organization.getDisplayName()).isNull();
        assertThat(organization.getDisplayReference()).isNull();
        // The identifier is still there for routing and diagnostics — it is just not a label.
        assertThat(organization.getId().toString()).isNotEqualTo(organization.getDisplayName());
    }

    @Test
    @DisplayName("Nested objects and arrays are walked, and paths locate the value in the raw payload")
    void walksNestedPayload() {
        lenient()
                .when(displayReferenceResolver.resolve(any(DisplayReferenceType.class), anyCollection()))
                .thenReturn(Map.of());

        Map<String, Object> payload = Map.of(
                "billDetails",
                Map.of(
                        "vendor_id", VENDOR_ID.toString(),
                        "lineItems", List.of(Map.of("invoiceId", INVOICE_ID.toString()))));

        List<EventPayloadReference> projection = projector.project(payload);

        assertThat(projection).hasSize(2);
        assertThat(byPath(projection, "billDetails.vendor_id").getReferenceType())
                .isEqualTo(DisplayReferenceType.VENDOR);
        // snake_case matched the same key as vendorId would have.
        assertThat(byPath(projection, "billDetails.lineItems[0].invoiceId").getId())
                .isEqualTo(INVOICE_ID);
    }

    @Test
    @DisplayName("Values that are not UUIDs, and keys that are not references, are left alone")
    void ignoresNonReferenceValues() {
        lenient()
                .when(displayReferenceResolver.resolve(any(DisplayReferenceType.class), anyCollection()))
                .thenReturn(Map.of());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invoiceId", "not-a-uuid"); // recognized key, unusable value
        payload.put("invoiceNumber", INVOICE_ID.toString()); // UUID-shaped, but not a reference key
        payload.put("totalAmount", "110.00");

        assertThat(projector.project(payload)).isEmpty();
        verify(displayReferenceResolver, never()).resolve(any(), anyCollection());
        verify(displayReferenceResolver, never()).resolveCodes(any(), anyCollection());
    }

    @Test
    @DisplayName("A code-valued locationId is projected and resolved by code, with no UUID (issue #1797)")
    void projectsCodeValuedLocationReference() {
        // Accounting's location dimension is a code, not a UUID — the canonical envelope carries
        // "location_id": "LOC_USA". Before #1797 the value never parsed as a UUID, so the key was
        // silently skipped and LOCATION was dead in the contract.
        when(displayReferenceResolver.resolveCodes(eq(DisplayReferenceType.LOCATION), anyCollection()))
                .thenReturn(Map.of("loc-107", new ResolvedDisplayReference("Planta Monterrey", "LOC-107")));

        Map<String, Object> payload = Map.of("dimensions", Map.of("location_id", "loc-107"));

        List<EventPayloadReference> projection = projector.project(payload);

        assertThat(projection).hasSize(1);
        EventPayloadReference location = byPath(projection, "dimensions.location_id");
        assertThat(location.getReferenceType()).isEqualTo(DisplayReferenceType.LOCATION);
        assertThat(location.getRawValue()).isEqualTo("loc-107");
        assertThat(location.getId()).isNull();
        assertThat(location.getDisplayName()).isEqualTo("Planta Monterrey");
        // The display reference is the canonical stored code, not the producer's spelling.
        assertThat(location.getDisplayReference()).isEqualTo("LOC-107");
        // Code-keyed types never go through the UUID entry point.
        verify(displayReferenceResolver, never()).resolve(any(), anyCollection());
    }

    @Test
    @DisplayName("An unknown location code is still projected, with null display values — never the code as a label")
    void unknownLocationCodeProjectsNullDisplayValues() {
        when(displayReferenceResolver.resolveCodes(eq(DisplayReferenceType.LOCATION), anyCollection()))
                .thenReturn(Map.of());

        List<EventPayloadReference> projection = projector.project(Map.of("locationId", "LOC_USA"));

        assertThat(projection).hasSize(1);
        EventPayloadReference location = projection.getFirst();
        assertThat(location.getRawValue()).isEqualTo("LOC_USA");
        assertThat(location.getId()).isNull();
        assertThat(location.getDisplayName()).isNull();
        assertThat(location.getDisplayReference()).isNull();
    }

    @Test
    @DisplayName("A UUID-shaped location value is still resolved by code, and keeps its parsed id")
    void uuidShapedLocationValueResolvesByCode() {
        when(displayReferenceResolver.resolveCodes(eq(DisplayReferenceType.LOCATION), anyCollection()))
                .thenReturn(Map.of());

        List<EventPayloadReference> projection = projector.project(Map.of("locationId", LOCATION_UUID.toString()));

        assertThat(projection).hasSize(1);
        assertThat(projection.getFirst().getRawValue()).isEqualTo(LOCATION_UUID.toString());
        assertThat(projection.getFirst().getId()).isEqualTo(LOCATION_UUID);
        // A profile could be coded with that exact string; the lookup is by code either way.
        verify(displayReferenceResolver).resolveCodes(eq(DisplayReferenceType.LOCATION), anyCollection());
        verify(displayReferenceResolver, never()).resolve(any(), anyCollection());
    }

    @Test
    @DisplayName("A hyphenated hex-like code never fabricates an id: only canonical UUID text parses")
    void doesNotFabricateIdFromLenientUuidParse() {
        // UUID.fromString accepts any five hyphen-separated hex groups, so "AB-CD-EF-01-23" would
        // parse to 000000ab-00cd-00ef-0001-000000000023 — an id that appears nowhere in the
        // payload. Under a code-keyed key the value is still a usable code; under a UUID-keyed
        // key it is not a reference at all.
        when(displayReferenceResolver.resolveCodes(eq(DisplayReferenceType.LOCATION), anyCollection()))
                .thenReturn(Map.of());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("locationId", "AB-CD-EF-01-23");
        payload.put("invoiceId", "AB-CD-EF-01-23");

        List<EventPayloadReference> projection = projector.project(payload);

        assertThat(projection).hasSize(1);
        assertThat(projection.getFirst().getPath()).isEqualTo("locationId");
        assertThat(projection.getFirst().getRawValue()).isEqualTo("AB-CD-EF-01-23");
        assertThat(projection.getFirst().getId()).isNull();
        verify(displayReferenceResolver, never()).resolve(any(), anyCollection());
    }

    @Test
    @DisplayName("Location values that cannot be a code are skipped: blank, non-string, or over-long")
    void ignoresUnusableLocationValues() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("locationId", "   ");
        payload.put("lines", List.of(Map.of("locationId", 107), Map.of("location_id", "x".repeat(101))));

        assertThat(projector.project(payload)).isEmpty();
        verify(displayReferenceResolver, never()).resolveCodes(any(), anyCollection());
    }

    @Test
    @DisplayName("Repeated location codes cost a single code-resolver call, alongside the UUID batches")
    void batchesCodeResolutionPerType() {
        when(displayReferenceResolver.resolveCodes(eq(DisplayReferenceType.LOCATION), anyCollection()))
                .thenReturn(Map.of("LOC-107", ResolvedDisplayReference.ofReference("LOC-107")));
        when(displayReferenceResolver.resolve(eq(DisplayReferenceType.INVOICE), anyCollection()))
                .thenReturn(Map.of());

        Map<String, Object> payload = Map.of(
                "invoiceId", INVOICE_ID.toString(),
                "lines", List.of(Map.of("locationId", "LOC-107"), Map.of("locationId", "LOC-107")));

        List<EventPayloadReference> projection = projector.project(payload);

        assertThat(projection).hasSize(3);
        assertThat(byPath(projection, "lines[0].locationId").getDisplayReference())
                .isEqualTo("LOC-107");
        assertThat(byPath(projection, "lines[1].locationId").getDisplayReference())
                .isEqualTo("LOC-107");
        verify(displayReferenceResolver, times(1)).resolveCodes(eq(DisplayReferenceType.LOCATION), anyCollection());
        verify(displayReferenceResolver, times(1)).resolve(eq(DisplayReferenceType.INVOICE), anyCollection());
    }

    @Test
    @DisplayName("Repeated references of one type cost a single resolver call")
    void batchesResolutionPerType() {
        when(displayReferenceResolver.resolve(eq(DisplayReferenceType.INVOICE), anyCollection()))
                .thenReturn(Map.of(INVOICE_ID, ResolvedDisplayReference.ofReference("INV-2026-004417")));

        Map<String, Object> payload = Map.of(
                "invoiceId", INVOICE_ID.toString(), "reversal", Map.of("originalInvoiceId", INVOICE_ID.toString()));

        List<EventPayloadReference> projection = projector.project(payload);

        assertThat(projection).hasSize(2);
        assertThat(projection)
                .allSatisfy(
                        reference -> assertThat(reference.getDisplayReference()).isEqualTo("INV-2026-004417"));
        verify(displayReferenceResolver, times(1)).resolve(eq(DisplayReferenceType.INVOICE), anyCollection());
    }

    @Test
    @DisplayName("The projector never modifies the payload it reads")
    void leavesRawPayloadUnchanged() {
        lenient()
                .when(displayReferenceResolver.resolve(any(DisplayReferenceType.class), anyCollection()))
                .thenReturn(Map.of());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invoiceId", INVOICE_ID.toString());
        payload.put("note", "unchanged");
        Map<String, Object> before = new LinkedHashMap<>(payload);

        projector.project(payload);

        assertThat(payload).isEqualTo(before);
    }

    @Test
    @DisplayName("Null values anywhere in the payload are walked past, not thrown on")
    void toleratesNullPayloadValues() {
        lenient()
                .when(displayReferenceResolver.resolve(any(DisplayReferenceType.class), anyCollection()))
                .thenReturn(Map.of());

        // Producer JSON routinely carries explicit nulls — an optional field the producer left
        // unset. A pattern switch throws on a null selector rather than falling through, so the
        // walk guards for it; without that guard the whole detail read answered 500.
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("invoiceId", null);
        nested.put("vendorId", VENDOR_ID.toString());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionDate", null);
        payload.put("billDetails", nested);
        payload.put("lineItems", java.util.Arrays.asList(null, Map.of("customerId", CUSTOMER_ID.toString())));

        List<EventPayloadReference> projection = projector.project(payload);

        assertThat(projection).hasSize(2);
        assertThat(byPath(projection, "billDetails.vendorId").getId()).isEqualTo(VENDOR_ID);
        assertThat(byPath(projection, "lineItems[1].customerId").getId()).isEqualTo(CUSTOMER_ID);
    }

    @Test
    @DisplayName("A recognized key holding an object is walked, not treated as a dead end")
    void walksIntoObjectValuedRecognizedKey() {
        lenient()
                .when(displayReferenceResolver.resolve(any(DisplayReferenceType.class), anyCollection()))
                .thenReturn(Map.of());

        // Producers legitimately wrap a reference in an object. The walk used to `continue`
        // unconditionally on a recognized key, so everything nested under one was dropped with no
        // log line — the outer key is unusable AND its contents vanished.
        Map<String, Object> payload =
                Map.of("customerId", Map.of("id", CUSTOMER_ID.toString(), "invoiceId", INVOICE_ID.toString()));

        List<EventPayloadReference> projection = projector.project(payload);

        assertThat(projection).hasSize(1);
        assertThat(byPath(projection, "customerId.invoiceId").getId()).isEqualTo(INVOICE_ID);
        assertThat(byPath(projection, "customerId.invoiceId").getReferenceType())
                .isEqualTo(DisplayReferenceType.INVOICE);
    }

    private static EventPayloadReference byPath(List<EventPayloadReference> projection, String path) {
        return projection.stream()
                .filter(reference -> path.equals(reference.getPath()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no projected reference at path " + path));
    }
}
