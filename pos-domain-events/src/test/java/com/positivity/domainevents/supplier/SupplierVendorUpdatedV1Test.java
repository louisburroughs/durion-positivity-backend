package com.positivity.domainevents.supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("SupplierVendorUpdatedV1 (#2516, #2621)")
class SupplierVendorUpdatedV1Test {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private static final UUID VENDOR_ID = UUID.fromString("01980a58-0000-7000-8000-0000000000b1");
    private static final Instant CREATED = Instant.parse("2026-10-05T12:00:00Z");

    private static SupplierVendorUpdatedV1.RemitTo remitTo() {
        return new SupplierVendorUpdatedV1.RemitTo(
                "Michelin North America",
                "1 Parkway S",
                null,
                "Greenville",
                "SC",
                "29615",
                "US",
                "ar@michelin.example");
    }

    private static SupplierVendorUpdatedV1 fact(List<SupplierVendorUpdatedV1.TaxRegistration> taxRegistrations) {
        return new SupplierVendorUpdatedV1(
                VENDOR_ID,
                "MICHELIN",
                "Michelin North America, Inc.",
                "Michelin",
                taxRegistrations,
                remitTo(),
                2,
                "NET30",
                "USD",
                SupplierVendorUpdatedV1.Status.ACTIVE,
                null,
                null,
                CREATED.plusSeconds(60),
                "clerk-a",
                "controller-b",
                "clerk-a",
                CREATED,
                CREATED.plusSeconds(60));
    }

    @Test
    @DisplayName("round-trips every field through JSON, last4 included")
    void roundTrips() {
        SupplierVendorUpdatedV1 fact = fact(List.of(
                new SupplierVendorUpdatedV1.TaxRegistration("SSN", null, "1234"),
                new SupplierVendorUpdatedV1.TaxRegistration("GST_HST", "ON", null)));

        SupplierVendorUpdatedV1 read = MAPPER.readValue(MAPPER.writeValueAsString(fact), SupplierVendorUpdatedV1.class);

        assertThat(read).isEqualTo(fact);
        assertThat(read.taxRegistrations().getFirst().last4()).isEqualTo("1234");
        assertThat(read.taxRegistrations().get(1).last4()).isNull();
        assertThat(read.isActive()).isTrue();
    }

    @Test
    @DisplayName("#2621: schema version 2 is the minimised registration shape")
    void schemaVersionIsTwo() {
        assertThat(SupplierVendorUpdatedV1.SCHEMA_VERSION).isEqualTo(2);
    }

    @Test
    @DisplayName("tax registrations are copied, so a later change to the caller's list cannot alter the fact")
    void taxRegistrationsListIsCopied() {
        List<SupplierVendorUpdatedV1.TaxRegistration> registrations = new ArrayList<>();
        registrations.add(new SupplierVendorUpdatedV1.TaxRegistration("GST_HST", "ON", "0001"));
        SupplierVendorUpdatedV1 fact = fact(registrations);

        registrations.clear();

        assertThat(fact.taxRegistrations()).hasSize(1);
    }

    /**
     * #2621 AC 1 (Security ruling on #2617, ruling 1): no registration number travels. A field-name walk
     * like {@link #carriesNoBankDetails}, so restoring a {@code number} component on the record fails it.
     */
    @Test
    @DisplayName("#2621 AC 1: a tax registration carries scheme, region and last4 only, never a number")
    void carriesNoRegistrationNumber() {
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(
                fact(List.of(new SupplierVendorUpdatedV1.TaxRegistration("SSN", null, "1234")))));

        JsonNode registration = json.get("taxRegistrations").get(0);
        List<String> registrationFields = new ArrayList<>();
        registration.properties().forEach(property -> registrationFields.add(property.getKey()));
        assertThat(registrationFields).containsExactlyInAnyOrder("scheme", "region", "last4");

        List<String> names = new ArrayList<>();
        collectFieldNames(json, names);
        assertThat(names)
                .as("no field anywhere on the fact may name a registration number")
                .noneMatch(name -> {
                    String lower = name.toLowerCase(java.util.Locale.ROOT);
                    return lower.equals("number")
                            || lower.contains("taxid")
                            || lower.contains("taxnumber")
                            || lower.equals("tin")
                            || lower.equals("ssn")
                            || lower.equals("sin");
                });
    }

    /**
     * A version 1 fact still exists in places a consumer can meet it: the broker until retention or the
     * alpha purge, a DLQ, and a replay window. It carries {@code number}. Mapping it must not fail, even
     * under a mapper that fails on unknown properties, and the number must not survive the read.
     */
    @Test
    @DisplayName("#2621: a v1-shaped fact with a registration number maps under a strict mapper and drops it")
    void readsAVersionOneFactAndDropsTheNumber() {
        ObjectMapper strict = JsonMapper.builder()
                .findAndAddModules()
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        String v1 = """
                {"vendorId":"01980a58-0000-7000-8000-0000000000b1","vendorNumber":"V-000001",
                 "legalName":"Legal","displayName":"Display",
                 "taxRegistrations":[{"scheme":"SSN","number":"000-00-1234","region":null}],
                 "remitTo":null,"remitToVersion":0,"defaultPaymentTerms":"NET30","defaultCurrency":"USD",
                 "status":"ACTIVE","statusChangedAt":null,"statusReason":null,"remitToChangedAt":null,
                 "remitToRequestedBy":null,"remitToApprovedBy":null,"createdBy":"clerk-a",
                 "createdAt":"2026-10-05T12:00:00Z","occurredAt":"2026-10-05T12:00:00Z"}
                """;

        SupplierVendorUpdatedV1 read = strict.readValue(v1, SupplierVendorUpdatedV1.class);

        assertThat(read.taxRegistrations())
                .containsExactly(new SupplierVendorUpdatedV1.TaxRegistration("SSN", null, null));
        assertThat(strict.writeValueAsString(read))
                .as("number absent")
                .doesNotContain("000-00-1234")
                .doesNotContain("\"number\"");
    }

    @Test
    @DisplayName("a negative remit-to version is refused")
    void negativeRemitToVersionRefused() {
        assertThatThrownBy(() -> new SupplierVendorUpdatedV1(
                        VENDOR_ID,
                        "V-000001",
                        "Legal",
                        "Display",
                        List.of(),
                        null,
                        -1,
                        null,
                        null,
                        SupplierVendorUpdatedV1.Status.ACTIVE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "clerk-a",
                        CREATED,
                        CREATED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AC 12: the payload carries no bank account data — no field names an account, routing or IBAN")
    void carriesNoBankDetails() {
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(fact(List.of())));

        List<String> names = new ArrayList<>();
        collectFieldNames(json, names);

        assertThat(names).noneMatch(name -> {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("bank")
                    || lower.contains("iban")
                    || lower.contains("routing")
                    || lower.contains("swift")
                    || lower.contains("accountnumber");
        });
    }

    private static void collectFieldNames(JsonNode node, List<String> names) {
        if (node.isObject()) {
            for (var property : node.properties()) {
                names.add(property.getKey());
                collectFieldNames(property.getValue(), names);
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                collectFieldNames(element, names);
            }
        }
    }
}
