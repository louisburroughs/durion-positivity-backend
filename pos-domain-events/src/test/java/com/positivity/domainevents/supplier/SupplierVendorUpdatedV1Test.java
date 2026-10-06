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

@DisplayName("SupplierVendorUpdatedV1 (#2516)")
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
    @DisplayName("round-trips every field through JSON")
    void roundTrips() {
        SupplierVendorUpdatedV1 fact =
                fact(List.of(new SupplierVendorUpdatedV1.TaxRegistration("EIN", "12-3456789", null)));

        SupplierVendorUpdatedV1 read = MAPPER.readValue(MAPPER.writeValueAsString(fact), SupplierVendorUpdatedV1.class);

        assertThat(read).isEqualTo(fact);
        assertThat(read.isActive()).isTrue();
    }

    @Test
    @DisplayName("tax registrations are copied, so a later change to the caller's list cannot alter the fact")
    void taxRegistrationsAreCopied() {
        List<SupplierVendorUpdatedV1.TaxRegistration> registrations = new ArrayList<>();
        registrations.add(new SupplierVendorUpdatedV1.TaxRegistration("GST_HST", "123456789RT0001", "ON"));
        SupplierVendorUpdatedV1 fact = fact(registrations);

        registrations.clear();

        assertThat(fact.taxRegistrations()).hasSize(1);
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
