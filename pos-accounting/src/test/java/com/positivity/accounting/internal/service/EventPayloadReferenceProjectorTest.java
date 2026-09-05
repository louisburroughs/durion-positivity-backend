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
 * Unit tests for the accounting-event payload display projection (issue #1778).
 *
 * <p>The projection's job is to let a screen label the UUID-backed values in an event payload
 * without exposing the UUIDs and without reaching across a domain boundary. These tests pin the
 * two halves of that: which values the walk recognizes in a free-form producer payload, and the
 * rule that an unresolvable reference yields nulls rather than the UUID as text.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventPayloadReferenceProjector Tests (issue #1778)")
class EventPayloadReferenceProjectorTest {

    private static final UUID INVOICE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0001");
    private static final UUID CUSTOMER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0002");
    private static final UUID VENDOR_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0003");
    private static final UUID ORGANIZATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0004");

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

    private static EventPayloadReference byPath(List<EventPayloadReference> projection, String path) {
        return projection.stream()
                .filter(reference -> path.equals(reference.getPath()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no projected reference at path " + path));
    }
}
