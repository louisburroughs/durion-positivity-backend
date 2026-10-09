package com.positivity.tax.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.tax.TaxPostgresContainer;
import com.positivity.tax.internal.security.FrontDoorSecretFilter;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * CAP:550 S32c ACs 1-3 end to end on Postgres: the front-door secret, the forwarded actor and tenant, replay,
 * overlap (service check and exclusion constraint), the outbox row per change, and the no-echo rule. Uses the
 * shipped placeholder profile's first regime (configuration, not tax law). Requires Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("pg")
@DisplayName("Tax registrations on Postgres through the front-door chain (CAP:550 S32c)")
class TaxRegistrationPostgresIT {

    private static final String SECRET = "test-only-accounting-front-door-secret";
    private static final String PATH = "/v1/tax/registrations";
    private static final String NUMBER = "123456789 RT 0001";
    private static final String STORED = "123456789RT0001";
    private static final String MALFORMED = "12345678 RT 0001";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TaxPostgresContainer.registerDataSourceProperties(registry);
        registry.add("pos.tax.front-doors.accounting-secret", () -> SECRET);
    }

    @Autowired
    private MockMvc mvc;

    private final JdbcTemplate owner = new JdbcTemplate(TaxPostgresContainer.ownerDataSource());

    private static String body(String number, String from, UUID requestId, String extra) {
        return """
                {"countryCode":"CA","regime":"GST_HST","registrationNumber":"%s","effectiveFrom":"%s",
                 "justification":"Registered with the tax authority","requestId":"%s"%s}
                """.formatted(number, from, requestId, extra);
    }

    private static MockHttpServletRequestBuilder write(
            MockHttpServletRequestBuilder request, String secret, UUID actor, UUID tenant) {
        MockHttpServletRequestBuilder built = request.contentType(MediaType.APPLICATION_JSON);
        if (secret != null) {
            built = built.header(FrontDoorSecretFilter.SECRET_HEADER, secret);
        }
        return built.header(FrontDoorSecretFilter.ACTOR_HEADER, actor.toString())
                .header("X-Tenant-Id", tenant.toString());
    }

    private int count(String table, UUID tenant) {
        Integer rows =
                owner.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, tenant);
        return rows == null ? 0 : rows;
    }

    @Test
    @DisplayName("AC 1: without the secret, or with a wrong one, the write is 401 and nothing is stored")
    void secretRequired() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();

        mvc.perform(write(post(PATH), null, actor, tenant).content(body(NUMBER, "2026-01-01", UUID.randomUUID(), "")))
                .andExpect(status().isUnauthorized());
        mvc.perform(write(post(PATH), "wrong", actor, tenant)
                        .content(body(NUMBER, "2026-01-01", UUID.randomUUID(), "")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(FrontDoorSecretFilter.CODE_SECRET_INVALID));

        assertThat(count("tax_registration", tenant)).isZero();
    }

    @Test
    @DisplayName("AC 1 and AC 3: create, replay, overlap and update; one history row and one outbox row per change,"
            + " and the history names the forwarded actor, never a body field")
    void writesReplaysAndRefusesOverlap() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String created = body(NUMBER, "2026-01-01", requestId, ",\"actor\":\"someone-else\",\"createdBy\":\"x\"");

        MvcResult first = mvc.perform(write(post(PATH), SECRET, actor, tenant).content(created))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.registrationNumber").value(STORED))
                .andExpect(jsonPath("$.jurisdictionCode").value("CA"))
                .andExpect(jsonPath("$.createdBy").value(actor.toString()))
                .andReturn();
        String registrationId =
                com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.registrationId");

        mvc.perform(write(post(PATH), SECRET, actor, tenant).content(created))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationId").value(registrationId));
        assertThat(count("tax_registration", tenant))
                .as("the replay stored nothing")
                .isEqualTo(1);
        assertThat(count("tax_registration_history", tenant)).isEqualTo(1);
        assertThat(count("event_outbox", tenant)).as("one fact per change").isEqualTo(1);
        assertThat(owner.queryForObject(
                        "SELECT actor FROM tax_registration_history WHERE tenant_id = ?", String.class, tenant))
                .isEqualTo(actor.toString());

        mvc.perform(write(post(PATH), SECRET, actor, tenant)
                        .content(body("987654321RT0001", "2026-06-01", UUID.randomUUID(), "")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TAX_REGISTRATION_OVERLAP"));

        mvc.perform(write(put(PATH + "/" + registrationId), SECRET, actor, tenant)
                        .content("""
                        {"registrationNumber":"%s","effectiveFrom":"2026-01-01","effectiveTo":"2026-05-31","version":0,
                         "justification":"Deregistered at the end of May","requestId":"%s"}
                        """.formatted(NUMBER, UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveTo").value("2026-05-31"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(write(put(PATH + "/" + registrationId), SECRET, actor, tenant)
                        .content("""
                        {"registrationNumber":"%s","effectiveFrom":"2026-01-01","version":0,
                         "justification":"Reopen with a stale version","requestId":"%s"}
                        """.formatted(NUMBER, UUID.randomUUID())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK"));
        mvc.perform(write(post(PATH), SECRET, actor, tenant)
                        .content(body("987654321RT0001", "2026-06-01", UUID.randomUUID(), "")))
                .andExpect(status().isCreated());

        assertThat(count("tax_registration", tenant)).isEqualTo(2);
        assertThat(count("tax_registration_history", tenant)).isEqualTo(3);
        assertThat(count("event_outbox", tenant)).isEqualTo(3);
    }

    @Test
    @DisplayName("ADR-0017 §2: a requestId reused with another number or dates, or for a change of another"
            + " registration, is 409 IDEMPOTENCY_CONFLICT and changes nothing")
    void requestIdReuseIsAConflict() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        MvcResult first = mvc.perform(
                        write(post(PATH), SECRET, actor, tenant).content(body(NUMBER, "2026-01-01", requestId, "")))
                .andExpect(status().isCreated())
                .andReturn();
        mvc.perform(write(post(PATH), SECRET, actor, tenant)
                        .content(body("987654321RT0001", "2026-01-01", requestId, "")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        MvcResult otherDates = mvc.perform(
                        write(post(PATH), SECRET, actor, tenant).content(body(NUMBER, "2026-02-01", requestId, "")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andReturn();
        assertThat(otherDates.getResponse().getContentAsString()).doesNotContain(STORED, NUMBER);

        MvcResult second = mvc.perform(
                        write(post(PATH), SECRET, actor, tenant).content("""
                                {"countryCode":"CA","regime":"QST","registrationNumber":"1234567890TQ0001",
                                 "effectiveFrom":"2026-01-01","justification":"Registered with the tax authority",
                                 "requestId":"%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn();
        String otherId =
                com.jayway.jsonpath.JsonPath.read(second.getResponse().getContentAsString(), "$.registrationId");
        mvc.perform(write(put(PATH + "/" + otherId), SECRET, actor, tenant).content("""
                        {"registrationNumber":"1234567890TQ0001","effectiveFrom":"2026-01-01","version":0,
                         "justification":"Reusing the first request id","requestId":"%s"}
                        """.formatted(requestId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertThat(count("tax_registration", tenant)).isEqualTo(2);
        assertThat(count("tax_registration_history", tenant)).isEqualTo(2);
        assertThat(count("event_outbox", tenant)).isEqualTo(2);
        assertThat((String)
                        com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.status"))
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("AC 2: a malformed number is 400 on registrationNumber; nothing is stored or queued, and the value"
            + " is in neither the body nor the DEBUG logs")
    void malformedNumberIsNeverStoredOrEchoed() throws Exception {
        UUID tenant = UUID.randomUUID();
        Logger root = (Logger) LoggerFactory.getLogger("com.positivity");
        Level before = root.getLevel();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        root.setLevel(Level.DEBUG);
        MvcResult result;
        try {
            result = mvc.perform(write(post(PATH), SECRET, UUID.randomUUID(), tenant)
                            .content(body(MALFORMED, "2026-01-01", UUID.randomUUID(), "")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("registrationNumber"))
                    .andReturn();
        } finally {
            root.detachAppender(logs);
            root.setLevel(before);
        }

        assertThat(result.getResponse().getContentAsString()).doesNotContain(MALFORMED, "12345678RT0001");
        assertThat(logs.list)
                .allSatisfy(event -> assertThat(event.getFormattedMessage())
                        .doesNotContain(MALFORMED)
                        .doesNotContain("12345678RT0001"));
        assertThat(count("tax_registration", tenant)).isZero();
        assertThat(count("event_outbox", tenant))
                .as("no tax.registration.changed queued")
                .isZero();
    }

    @Test
    @DisplayName("the exclusion constraint refuses two overlapping rows that skip the service check")
    void exclusionConstraintIsTheBackstop() {
        UUID tenant = UUID.randomUUID();
        String insert = "INSERT INTO tax_registration (tenant_id, id, country_code, regime, registration_number,"
                + " jurisdiction_code, effective_from, effective_to, version, created_at, created_by, updated_at,"
                + " updated_by) VALUES (?, ?, 'CA', 'GST_HST', '123456789RT0001', 'CA', ?::date, NULL, 0, now(), 'a',"
                + " now(), 'a')";
        owner.update(insert, tenant, UUID.randomUUID(), "2026-01-01");

        assertThatThrownBy(() -> owner.update(insert, tenant, UUID.randomUUID(), "2026-03-01"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("tax_registration_no_overlap");
        owner.update(insert, UUID.randomUUID(), UUID.randomUUID(), "2026-03-01");
    }
}
