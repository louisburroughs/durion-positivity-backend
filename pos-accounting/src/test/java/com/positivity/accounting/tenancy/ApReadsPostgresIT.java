package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.dto.ApApprovalPolicyResponse;
import com.positivity.accounting.internal.dto.VendorBillExpenseCategoryListResponse;
import com.positivity.accounting.internal.service.ActorDisplayNames;
import com.positivity.accounting.internal.service.ApApprovalPolicyService;
import com.positivity.accounting.internal.service.ApChoicesService;
import com.positivity.accounting.internal.service.PeopleContactEventsListener;
import com.positivity.accounting.internal.service.PeopleContactReplica;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * AP reads (#2670) on the real schema: the V25 people-contact copy under row-level security, the seed's plain expense
 * labels on a newly provisioned tenant, the expense-category read over the template's keys and mappings, and the
 * people-contact facts fed through the listener to the AP approval policy history's {@code changedByName}.
 *
 * <p>Kafka rails are off in the {@code pg} profile, so the listener is built by hand over the real {@link
 * PeopleContactReplica}, as the container would call it with the record's tenant bound. Every test works on tenants
 * of its own and removes their rows afterwards.
 *
 * <p>Requires Docker.
 */
@DisplayName("AP reads on Postgres: expense categories, seed labels, the people-contact copy and actor names (#2670)")
class ApReadsPostgresIT extends PostgresTenancyTestBase {

    private static final List<String> LABELS = List.of(
            "Building repairs",
            "Cleaning and janitorial",
            "Equipment repairs",
            "Office supplies",
            "Postage and shipping",
            "Shop supplies",
            "Small tools",
            "Staff meals",
            "Vehicle fuel");

    @Autowired
    private ApChoicesService apChoices;

    @Autowired
    private ApApprovalPolicyService approvalPolicy;

    @Autowired
    private ActorDisplayNames actorNames;

    @Autowired
    private PeopleContactReplica replica;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<UUID> tenants = new ArrayList<>();

    @AfterEach
    void removeTestTenants() {
        TenantContext.clear();
        JdbcTemplate owner = owner();
        List<String> scoped = owner.queryForList(
                "SELECT table_name FROM information_schema.columns WHERE table_schema = 'public'"
                        + " AND column_name = 'tenant_id' ORDER BY table_name",
                String.class);
        for (UUID tenant : tenants) {
            // Foreign keys decide the order; a few passes settle it without naming it here.
            for (int pass = 0; pass < 8; pass++) {
                boolean blocked = false;
                for (String table : scoped) {
                    try {
                        owner.update("DELETE FROM " + table + " WHERE tenant_id = ?", tenant);
                    } catch (RuntimeException stillReferenced) {
                        blocked = true;
                    }
                }
                if (!blocked) {
                    break;
                }
            }
        }
        tenants.clear();
    }

    private static JdbcTemplate owner() {
        return new JdbcTemplate(ownerDataSource());
    }

    private UUID provisionedTenant() {
        UUID tenant = tenantWithZone();
        tenants.add(tenant);
        provisionAccounting(tenant);
        return tenant;
    }

    private UUID bareTenant() {
        UUID tenant = UUIDv7Generator.generate();
        tenants.add(tenant);
        return tenant;
    }

    // ---- AC 12 and AC 1: the seed's labels and the expense-category read ----------------------------------------

    @Test
    @DisplayName("AC 12: a newly provisioned tenant's nine VENDOR_BILL expense keys carry the plain labels, with the"
            + " template's mappings unchanged")
    void seedLabels() {
        UUID tenant = provisionedTenant();

        List<String> labels = owner().queryForList(
                        "SELECT k.description FROM mapping_key k JOIN posting_category c ON c.posting_category_id ="
                                + " k.posting_category_id AND c.tenant_id = k.tenant_id WHERE k.tenant_id = ? AND"
                                + " c.category_name = 'VENDOR_BILL' AND k.key_name LIKE 'EXPENSE\\_%' ORDER BY"
                                + " k.description",
                        String.class, tenant);
        assertThat(labels).containsExactlyElementsOf(LABELS);
        assertThat(expenseAccounts(tenant))
                .as("each key posts to the template's account")
                .isEqualTo(expenseAccounts(PlatformTenant.ID))
                .containsEntry("EXPENSE_SHOP_SUPPLIES", "6340")
                .hasSize(9);
    }

    private Map<String, String> expenseAccounts(UUID tenant) {
        Map<String, String> accounts = new java.util.TreeMap<>();
        owner().query(
                        "SELECT k.key_name, a.account_code FROM gl_mapping m JOIN mapping_key k ON k.mapping_key_id ="
                                + " m.mapping_key_id JOIN posting_category c ON c.posting_category_id ="
                                + " m.posting_category_id JOIN gl_account a ON a.gl_account_id = m.gl_account_id"
                                + " WHERE m.tenant_id = ? AND c.category_name = 'VENDOR_BILL' AND k.key_name LIKE"
                                + " 'EXPENSE\\_%'",
                        (java.sql.ResultSet rs) -> {
                            accounts.put(rs.getString(1), rs.getString(2));
                        },
                        tenant);
        return accounts;
    }

    @Test
    @DisplayName("AC 1: with EXPENSE_SMALL_TOOLS deactivated and EXPENSE_STAFF_MEALS without an effective mapping, the"
            + " read lists eight categories by label, Shop supplies on 6340 and Staff meals without an account")
    void expenseCategories() {
        UUID tenant = provisionedTenant();
        JdbcTemplate owner = owner();
        owner.update(
                "UPDATE mapping_key SET is_active = false WHERE tenant_id = ? AND key_name = 'EXPENSE_SMALL_TOOLS'",
                tenant);
        owner.update(
                "UPDATE gl_mapping SET effective_end_date = TIMESTAMP '2020-01-02 00:00:00' WHERE tenant_id = ? AND"
                        + " mapping_key_id IN (SELECT k.mapping_key_id FROM mapping_key k JOIN posting_category c ON"
                        + " c.posting_category_id = k.posting_category_id WHERE k.tenant_id = ? AND"
                        + " c.category_name = 'VENDOR_BILL' AND k.key_name = 'EXPENSE_STAFF_MEALS')",
                tenant,
                tenant);

        VendorBillExpenseCategoryListResponse listed = asTenant(tenant, () -> apChoices.expenseCategories());

        assertThat(listed.categories())
                .extracting(VendorBillExpenseCategoryListResponse.Category::label)
                .containsExactlyElementsOf(LABELS.stream()
                        .filter(label -> !label.equals("Small tools"))
                        .toList());
        assertThat(listed.categories())
                .filteredOn(c -> c.mappingKey().equals("EXPENSE_SHOP_SUPPLIES"))
                .singleElement()
                .satisfies(c -> assertThat(c.accountNumber()).isEqualTo("6340"));
        assertThat(listed.categories())
                .filteredOn(c -> c.mappingKey().equals("EXPENSE_STAFF_MEALS"))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.accountNumber()).isNull();
                    assertThat(c.accountName()).isNull();
                });
    }

    // ---- AC 6, 7, 9 and 10: the people-contact copy and the names ----------------------------------------------

    private static String personUpdated(UUID personId, long version, String first, String last) {
        return """
            {"eventId":"%s","eventType":"people-contact.person.updated","schemaVersion":1,"aggregateId":"%s",
             "aggregateVersion":%d,"payload":{"personId":"%s","firstName":"%s","lastName":"%s",
             "preferredName":null,"contactPoints":[],"postalAddress":null}}
            """.formatted(UUIDv7Generator.generate(), personId, version, personId, first, last);
    }

    private static String linkUpdated(UUID linkId, UUID personId, String username) {
        return """
            {"eventId":"%s","eventType":"people-contact.user-person-link.updated","aggregateId":"%s",
             "aggregateVersion":1,"payload":{"linkId":"%s","personId":"%s","username":"%s","status":"ACTIVE"}}
            """.formatted(UUIDv7Generator.generate(), linkId, linkId, personId, username);
    }

    private static String linkRemoved(UUID linkId, UUID personId, String username) {
        return """
            {"eventId":"%s","eventType":"people-contact.user-person-link.removed","aggregateId":"%s",
             "aggregateVersion":2,"payload":{"linkId":"%s","personId":"%s","username":"%s"}}
            """.formatted(UUIDv7Generator.generate(), linkId, linkId, personId, username);
    }

    private static void policyChangedBy(UUID tenant, String username) {
        owner().update(
                        "INSERT INTO accounting_audit_log (tenant_id, \"timestamp\", audit_log_id, entity_id,"
                            + " entity_type, operation, user_id, justification, new_value, old_value) VALUES (?, ?, ?,"
                            + " ?, 'AP_APPROVAL_POLICY', 'AP_APPROVAL_POLICY_SET', ?, 'Routine parts bills',"
                            + " 'setting=AP_CLERK_APPROVAL_LIMIT;value=2500.00;roles=CONTROLLER', '0.00')",
                        tenant,
                        java.sql.Timestamp.from(Instant.parse("2026-10-09T10:00:00Z")),
                        UUIDv7Generator.generate(),
                        UUIDv7Generator.generate(),
                        username);
    }

    @Test
    @DisplayName("Kafka path: person.updated and user-person-link.updated through the listener name the policy"
            + " history's actor; link removal makes the next read null")
    void factsToPolicyHistory() {
        UUID tenant = tenantWithZone();
        tenants.add(tenant);
        PeopleContactEventsListener listener = new PeopleContactEventsListener(objectMapper, replica);
        UUID person = UUIDv7Generator.generate();
        UUID link = UUIDv7Generator.generate();
        policyChangedBy(tenant, "controller.cfo");

        asTenant(tenant, () -> {
            listener.onPeopleContactEvent(personUpdated(person, 100, "Dana", "Reyes"));
            listener.onPeopleContactEvent(linkUpdated(link, person, "controller.cfo"));
        });

        ApApprovalPolicyResponse named = asTenant(tenant, () -> approvalPolicy.get(0, 20));
        assertThat(named.history()).singleElement().satisfies(row -> {
            assertThat(row.changedBy()).isEqualTo("controller.cfo");
            assertThat(row.changedByName()).isEqualTo("Dana Reyes");
        });

        // An older person fact leaves the newer name in place (AC 9).
        asTenant(tenant, () -> listener.onPeopleContactEvent(personUpdated(person, 99, "Old", "Name")));
        assertThat(asTenant(tenant, () -> approvalPolicy.get(0, 20))
                        .history()
                        .getFirst()
                        .changedByName())
                .isEqualTo("Dana Reyes");

        asTenant(tenant, () -> listener.onPeopleContactEvent(linkRemoved(link, person, "controller.cfo")));
        assertThat(asTenant(tenant, () -> approvalPolicy.get(0, 20))
                        .history()
                        .getFirst()
                        .changedByName())
                .as("no ACTIVE link: null, never the username")
                .isNull();
        assertThat(owner().queryForObject(
                                "SELECT count(*) FROM processed_events WHERE tenant_id = ? AND owner ="
                                        + " 'people-contact'",
                                Integer.class,
                                tenant))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("AC 10 and RLS: the same username linked in two tenants to different people serves each tenant its own"
            + " person's name; another tenant's copy rows are invisible, raw SQL included")
    void isolation() {
        UUID one = bareTenant();
        UUID two = bareTenant();
        PeopleContactEventsListener listener = new PeopleContactEventsListener(objectMapper, replica);
        UUID personOne = UUIDv7Generator.generate();
        UUID personTwo = UUIDv7Generator.generate();
        asTenant(one, () -> {
            listener.onPeopleContactEvent(personUpdated(personOne, 1, "Dana", "Reyes"));
            listener.onPeopleContactEvent(linkUpdated(UUIDv7Generator.generate(), personOne, "controller.cfo"));
        });
        asTenant(two, () -> {
            listener.onPeopleContactEvent(personUpdated(personTwo, 1, "Kofi", "Mensah"));
            listener.onPeopleContactEvent(linkUpdated(UUIDv7Generator.generate(), personTwo, "controller.cfo"));
        });

        assertThat(asTenant(one, () -> actorNames.namesOf(List.of("controller.cfo"))))
                .containsExactly(Map.entry("controller.cfo", "Dana Reyes"));
        assertThat(asTenant(two, () -> actorNames.namesOf(List.of("controller.cfo"))))
                .containsExactly(Map.entry("controller.cfo", "Kofi Mensah"));

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        asTenant(
                one,
                () -> tx.executeWithoutResult(status -> {
                    for (String table : List.of("ext_people_contact_person", "ext_people_contact_user_link")) {
                        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class))
                                .as("tenant one sees only its own %s row", table)
                                .isEqualTo(1);
                        assertThat(jdbc.queryForObject(
                                        "SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, two))
                                .as("tenant one sees none of tenant two's %s rows through raw SQL", table)
                                .isZero();
                    }
                }));
        tx.executeWithoutResult(status -> {
            for (String table : List.of("ext_people_contact_person", "ext_people_contact_user_link")) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class))
                        .as("an unbound connection sees no %s row", table)
                        .isZero();
            }
        });
    }
}
