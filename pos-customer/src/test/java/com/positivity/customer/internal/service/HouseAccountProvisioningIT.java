package com.positivity.customer.internal.service;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.customer.CustomerPostgresContainer;
import com.positivity.customer.internal.config.OutboxEventWriter;
import com.positivity.customer.internal.domain.PartyAttributes;
import com.positivity.customer.internal.domain.SegmentPredicate;
import com.positivity.customer.internal.dto.CreateCommercialAccountRequest;
import com.positivity.customer.internal.dto.DuplicateCheckResponse;
import com.positivity.customer.internal.dto.MergePartiesRequest;
import com.positivity.customer.internal.dto.SearchPartiesResponse;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.Segment;
import com.positivity.customer.internal.enums.AccountStatus;
import com.positivity.customer.internal.enums.AccountTier;
import com.positivity.customer.internal.enums.AudienceType;
import com.positivity.customer.internal.enums.HouseAccountKind;
import com.positivity.customer.internal.enums.LifecycleStage;
import com.positivity.customer.internal.enums.SegmentOperator;
import com.positivity.customer.internal.enums.SegmentType;
import com.positivity.customer.internal.exception.HouseAccountImmutableException;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.OutboxEventRepository;
import com.positivity.customer.tenancy.PostgresTenancyTestBase;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantRegistry;
import com.positivity.tenancy.TenantResolver;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The CASH house account against the real PostgreSQL schema, as the non-owner {@code pos_app} role
 * under row-level security (CAP:550 S7, #2505): provisioning over two tenants at startup, the
 * sweep's idempotency and its race on the partial unique index, a tenant added after startup, the
 * guards end to end, the read flag, the analytic exclusions, and tenant isolation.
 *
 * <p>This is the one context in the module that switches the provisioner on (the test profiles
 * turn it off, because it commits into the database every context shares) and that carries an
 * {@link OutboxEventWriter}, so the {@code customer.party.updated} facts can be read back from
 * {@code event_outbox}. Everything the class commits is removed in {@link #removeWhatThisClassCommitted}.
 *
 * <p>Requires Docker.
 */
@DisplayName("CASH house account on Postgres (CAP:550 S7)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(
        properties = {
            "pos.customer.house-account.enabled=true",
            // Far beyond the test run: every sweep after startup is one the test calls itself.
            "pos.customer.house-account.sweep-interval-ms=86400000"
        })
@Import(HouseAccountProvisioningIT.ProvisioningTestConfig.class)
class HouseAccountProvisioningIT extends PostgresTenancyTestBase {

    /** A tenant that joins the registry after startup (acceptance criterion 3). */
    private static final UUID TENANT_LATE = UUID.fromString("01900000-0000-7000-8000-0000000000c3");

    /** A tenant no sweep ever visits; only the race test provisions it. */
    private static final UUID TENANT_RACE = UUID.fromString("01900000-0000-7000-8000-0000000000c4");

    private static final List<UUID> TENANTS = List.of(TENANT_A, TENANT_B, TENANT_LATE, TENANT_RACE);

    @TestConfiguration
    static class ProvisioningTestConfig {

        /** The fleet the sweep visits; mutable so a test can add a tenant after startup. */
        @Bean
        MutableTenantRegistry tenantRegistry() {
            return new MutableTenantRegistry(TENANT_A, TENANT_B);
        }

        /** The outbox writer is {@code @KafkaRails}, so the broker-less {@code pg} profile has none. */
        @Bean
        OutboxEventWriter outboxEventWriter(
                Clock clock,
                ObjectMapper objectMapper,
                OutboxEventRepository outboxEventRepository,
                TenantResolver tenantResolver) {
            return new OutboxEventWriter(clock, objectMapper, outboxEventRepository, tenantResolver);
        }
    }

    static final class MutableTenantRegistry implements TenantRegistry {
        private final CopyOnWriteArrayList<UUID> tenants = new CopyOnWriteArrayList<>();

        MutableTenantRegistry(UUID... initial) {
            tenants.addAll(List.of(initial));
        }

        void add(UUID tenantId) {
            tenants.addIfAbsent(tenantId);
        }

        @Override
        public List<UUID> activeTenantIds() {
            return List.copyOf(tenants);
        }
    }

    @Autowired
    private HouseAccountProvisioner provisioner;

    @Autowired
    private HouseAccountProvisioningService provisioningService;

    @Autowired
    private HouseAccountGuard guard;

    @Autowired
    private MutableTenantRegistry registry;

    @Autowired
    private CommercialPartyRepository parties;

    @Autowired
    private PartyService partyService;

    @Autowired
    private SegmentResolutionService segmentResolution;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    private final JdbcTemplate owner = new JdbcTemplate(CustomerPostgresContainer.ownerDataSource());

    /** Ordinary parties this class created, removed with everything else at the end. */
    private final List<UUID> createdParties = new CopyOnWriteArrayList<>();

    @AfterEach
    void unbind() {
        TenantContext.clear();
    }

    @AfterAll
    void removeWhatThisClassCommitted() {
        List<UUID> partyIds = new ArrayList<>(createdParties);
        for (UUID tenant : TENANTS) {
            partyIds.addAll(owner.queryForList(
                    "SELECT customer_id FROM commercial_party WHERE house_account IS NOT NULL AND tenant_id = ?",
                    UUID.class,
                    tenant));
        }
        for (UUID partyId : partyIds) {
            owner.update("DELETE FROM event_outbox WHERE record_key = ?", partyId.toString());
            owner.update("DELETE FROM commercial_party WHERE customer_id = ?", partyId);
        }
    }

    // ---- provisioning ------------------------------------------------------------------------

    @Test
    @DisplayName("AC1: startup gives each active tenant exactly one CASH account and one flagged fact")
    void startupProvisionsEveryActiveTenant() {
        for (UUID tenant : List.of(TENANT_A, TENANT_B)) {
            List<Map<String, Object>> accounts = houseAccountRows(tenant);
            assertThat(accounts).as("tenant %s", tenant).hasSize(1);
            Map<String, Object> account = accounts.getFirst();
            assertThat(account.get("house_account")).isEqualTo("CASH_SALE");
            assertThat(account.get("customer_number")).isEqualTo("CASH");
            assertThat(account.get("legal_name")).isEqualTo("Walk-in customer");
            assertThat(account.get("display_name")).isEqualTo("Walk-in customer");
            assertThat(((Number) account.get("status")).intValue()).isEqualTo(AccountStatus.ACTIVE.ordinal());
            assertThat(((Number) account.get("tier")).intValue()).isEqualTo(AccountTier.STANDARD.ordinal());
            assertThat(account.get("tier_manual_override")).isEqualTo(true);
            assertThat(account.get("lifecycle_stage")).isEqualTo(LifecycleStage.ACTIVE.name());
            assertThat(account.get("account_marketing_opt_out")).isEqualTo(true);
            // No personal data and no billing rules: on-account terms cannot exist for it.
            assertThat(account.get("tax_id")).isNull();
            assertThat(account.get("primary_address")).isNull();
            assertThat(account.get("br_payment_terms")).isNull();
            assertThat(account.get("br_credit_limit")).isNull();

            UUID partyId = (UUID) account.get("customer_id");
            List<JsonNode> facts = partyFacts(tenant, partyId);
            assertThat(facts).as("one creation fact for tenant %s", tenant).hasSize(1);
            JsonNode fact = facts.getFirst();
            assertThat(fact.path("sourceService").stringValue()).isEqualTo("pos-customer");
            assertThat(fact.path("tenantId").stringValue()).isEqualTo(tenant.toString());
            assertThat(fact.path("payload").path("houseAccount").stringValue()).isEqualTo("CASH_SALE");
            assertThat(fact.path("payload").path("customerNumber").stringValue())
                    .isEqualTo("CASH");
            assertThat(fact.path("payload").path("status").stringValue()).isEqualTo("ACTIVE");
            assertThat(fact.path("payload").path("requirementsMet").booleanValue())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("AC2: sweeping again creates no second account and no second fact")
    void sweepIsIdempotent() {
        UUID before = houseAccountId(TENANT_A);

        provisioner.sweep();
        provisioner.sweep();

        assertThat(houseAccountRows(TENANT_A)).hasSize(1);
        assertThat(houseAccountId(TENANT_A)).isEqualTo(before);
        assertThat(partyFacts(TENANT_A, before)).hasSize(1);
        assertThat(houseAccountRows(TENANT_B)).hasSize(1);
    }

    @Test
    @DisplayName("AC2: two instances racing on one tenant leave one account and one fact")
    void concurrentProvisioningLosesOnTheUniqueIndex() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        List<CompletableFuture<HouseAccountProvisioner.Outcome>> second = new ArrayList<>();

        // The first instance inserts and holds its transaction open. The second sees no account
        // (the insert is uncommitted), inserts too, and blocks on commercial_party_house_account_uk
        // until the first commits — at which point the index refuses it.
        UUID winner = asTenant(
                TENANT_RACE,
                () -> transaction.execute(status -> {
                    UUID created =
                            provisioningService.createCashAccountIfMissing().orElseThrow();
                    second.add(CompletableFuture.supplyAsync(
                            () -> TenantContext.callAs(TENANT_RACE, () -> provisioner.provisionTenant(TENANT_RACE))));
                    awaitABlockedInsert();
                    return created;
                }));

        assertThat(second.getFirst().get(30, TimeUnit.SECONDS))
                .as("the loser treats the refused insert as already provisioned")
                .isEqualTo(HouseAccountProvisioner.Outcome.EXISTING);
        assertThat(houseAccountRows(TENANT_RACE)).hasSize(1);
        assertThat(houseAccountId(TENANT_RACE)).isEqualTo(winner);
        assertThat(partyFacts(TENANT_RACE, winner))
                .as("the loser's fact rolled back with its insert")
                .hasSize(1);
    }

    @Test
    @DisplayName("AC3: a tenant added to the registry after startup is provisioned by the next sweep")
    void aTenantAddedLaterIsProvisionedByTheNextSweep() {
        assertThat(houseAccountRows(TENANT_LATE)).isEmpty();

        registry.add(TENANT_LATE);
        provisioner.sweep();

        assertThat(houseAccountRows(TENANT_LATE)).hasSize(1);
        assertThat(partyFacts(TENANT_LATE, houseAccountId(TENANT_LATE))).hasSize(1);
    }

    @Test
    @DisplayName("the database refuses a second house account for a tenant, and any unknown kind")
    void theSchemaEnforcesOnePerTenant() {
        assertThatThrownBy(() -> owner.update("""
                        INSERT INTO commercial_party (tenant_id, customer_id, party_type, status, tier,
                            tier_manual_override, created_at, updated_at, customer_number, legal_name, house_account)
                        VALUES (?, ?, 0, 0, 0, true, now(), now(), 'CASH-2', 'Second walk-in', 'CASH_SALE')
                        """, TENANT_A, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("commercial_party_house_account_uk");
        assertThatThrownBy(() -> owner.update("""
                        INSERT INTO commercial_party (tenant_id, customer_id, party_type, status, tier,
                            tier_manual_override, created_at, updated_at, customer_number, legal_name, house_account)
                        VALUES (?, ?, 0, 0, 0, true, now(), now(), 'HOUSE-X', 'Other house', 'ON_ACCOUNT')
                        """, TENANT_A, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("commercial_party_house_account_chk");
    }

    // ---- guards, end to end ------------------------------------------------------------------

    @Test
    @DisplayName("AC4: merging the house account as survivor or loser changes nothing and queues no fact")
    void mergeIsRefusedBothWays() {
        UUID house = houseAccountId(TENANT_A);
        UUID ordinary = createOrdinaryParty(TENANT_A, "Merge Candidate " + UUID.randomUUID());
        long houseVersion = version(house);
        long ordinaryVersion = version(ordinary);
        int houseFacts = partyFacts(TENANT_A, house).size();
        int ordinaryFacts = partyFacts(TENANT_A, ordinary).size();

        asTenant(TENANT_A, () -> {
            assertThatThrownBy(() -> partyService.mergeParties(house, merge(ordinary)))
                    .isInstanceOf(HouseAccountImmutableException.class);
            assertThatThrownBy(() -> partyService.mergeParties(ordinary, merge(house)))
                    .isInstanceOf(HouseAccountImmutableException.class);
        });

        assertThat(version(house)).isEqualTo(houseVersion);
        assertThat(version(ordinary)).isEqualTo(ordinaryVersion);
        assertThat(status(house)).isEqualTo(AccountStatus.ACTIVE.ordinal());
        assertThat(status(ordinary)).isEqualTo(AccountStatus.ACTIVE.ordinal());
        assertThat(partyFacts(TENANT_A, house)).hasSize(houseFacts);
        assertThat(partyFacts(TENANT_A, ordinary)).hasSize(ordinaryFacts);
    }

    @Test
    @DisplayName("an ordinary party can never be turned into a house account by a save")
    void theMarkerIsNotUpdatable() {
        UUID ordinary = createOrdinaryParty(TENANT_B, "Never A House Account " + UUID.randomUUID());

        asTenant(TENANT_B, () -> {
            CommercialParty party = parties.findById(ordinary).orElseThrow();
            party.setHouseAccount(HouseAccountKind.CASH_SALE);
            party.setDisplayName("Renamed");
            parties.saveAndFlush(party);
        });

        assertThat(owner.queryForObject(
                        "SELECT house_account FROM commercial_party WHERE customer_id = ?", String.class, ordinary))
                .isNull();
        assertThat(houseAccountRows(TENANT_B)).hasSize(1);
    }

    // ---- facts and reads ---------------------------------------------------------------------

    @Test
    @DisplayName("AC6: an ordinary party's fact carries houseAccount = null")
    void ordinaryPartyFactHasNoFlag() {
        UUID ordinary = createOrdinaryParty(TENANT_A, "Ordinary Fact " + UUID.randomUUID());

        List<JsonNode> facts = partyFacts(TENANT_A, ordinary);

        assertThat(facts).isNotEmpty();
        assertThat(facts).allSatisfy(fact -> {
            assertThat(fact.path("payload").has("houseAccount")).isTrue();
            assertThat(fact.path("payload").path("houseAccount").isNull()).isTrue();
        });
    }

    @Test
    @DisplayName("AC8: party reads return the house account flagged and ordinary parties unflagged")
    void readsCarryTheFlag() {
        UUID house = houseAccountId(TENANT_A);
        UUID ordinary = createOrdinaryParty(TENANT_A, "Ordinary Read " + UUID.randomUUID());

        asTenant(TENANT_A, () -> {
            assertThat(partyService.getParty(house).getHouseAccount()).isEqualTo("CASH_SALE");
            assertThat(partyService.getParty(ordinary).getHouseAccount()).isNull();

            // Browse and search still return it; a consumer decides whether to show it (S10).
            List<SearchPartiesResponse.PartySummary> browsed = partyService
                    .browseParties(PageRequest.of(0, 200), null, null, null, "CASH", null, null)
                    .getResults();
            assertThat(browsed)
                    .extracting(
                            SearchPartiesResponse.PartySummary::getPartyId,
                            SearchPartiesResponse.PartySummary::getHouseAccount)
                    .containsExactly(org.assertj.core.api.Assertions.tuple(house.toString(), "CASH_SALE"));
            assertThat(partyService.searchParties(null).getResults())
                    .filteredOn(summary -> summary.getPartyId().equals(ordinary.toString()))
                    .singleElement()
                    .extracting(SearchPartiesResponse.PartySummary::getHouseAccount)
                    .isNull();
        });
    }

    // ---- analytic exclusions -----------------------------------------------------------------

    @Test
    @DisplayName("AC7: a dynamic segment matching every commercial party never contains the house account")
    void dynamicSegmentExcludesTheHouseAccount() {
        UUID house = houseAccountId(TENANT_A);
        UUID ordinary = createOrdinaryParty(TENANT_A, "Segment Member " + UUID.randomUUID());
        Segment everyCommercialParty = Segment.builder()
                .segmentId(UUID.randomUUID())
                .name("Every commercial party")
                .audienceType(AudienceType.COMMERCIAL)
                .type(SegmentType.DYNAMIC)
                .active(true)
                .build();
        SegmentPredicate matchesAll =
                new SegmentPredicate.Comparison("party.partyType", SegmentOperator.EQUALS, List.of("COMMERCIAL"));

        asTenant(TENANT_A, () -> {
            List<UUID> members = segmentResolution
                    .resolve(everyCommercialParty, Optional.of(matchesAll))
                    .partyIds();
            assertThat(members).contains(ordinary).doesNotContain(house);
            assertThat(segmentResolution.loadCandidates(AudienceType.COMMERCIAL))
                    .extracting(PartyAttributes::partyId)
                    .contains(ordinary)
                    .doesNotContain(house);
            assertThat(segmentResolution.loadAttributes(AudienceType.COMMERCIAL, List.of(house, ordinary)))
                    .extracting(PartyAttributes::partyId)
                    .containsExactly(ordinary);
        });
    }

    @Test
    @DisplayName("AC7: the duplicate check for \"Walk-in customer\" never returns the house account")
    void duplicateCheckExcludesTheHouseAccount() {
        UUID house = houseAccountId(TENANT_A);

        DuplicateCheckResponse response =
                asTenant(TENANT_A, () -> partyService.checkPartyDuplicates("Walk-in customer"));

        assertThat(response.getExactMatchPartyId()).isNotEqualTo(house.toString());
        assertThat(response.getPotentialDuplicates())
                .extracting(DuplicateCheckResponse.PartyMatch::getPartyId)
                .doesNotContain(house.toString());
    }

    // ---- tenant isolation --------------------------------------------------------------------

    @Test
    @DisplayName("AC9: another tenant's house account is not a row: 404 on read, and the guard leaks nothing")
    void anotherTenantsHouseAccountIsInvisible() {
        UUID houseOfB = houseAccountId(TENANT_B);
        UUID ordinaryOfA = createOrdinaryParty(TENANT_A, "Isolation " + UUID.randomUUID());

        asTenant(TENANT_A, () -> {
            assertThatThrownBy(() -> partyService.getParty(houseOfB))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
            // The guard passes: tenant A must not learn that the id is a house account elsewhere.
            assertThatCode(() -> guard.requireNotHouseAccount(houseOfB)).doesNotThrowAnyException();
            assertThat(parties.existsByPartyIdAndHouseAccountIsNotNull(houseOfB))
                    .isFalse();
            // So a guarded write answers its ordinary not-found, not HOUSE_ACCOUNT_IMMUTABLE.
            assertThatThrownBy(() -> partyService.mergeParties(ordinaryOfA, merge(houseOfB)))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
            assertThat(parties.findByHouseAccount(HouseAccountKind.CASH_SALE))
                    .map(CommercialParty::getPartyId)
                    .contains(houseAccountId(TENANT_A));
        });
        assertThat(status(houseOfB)).isEqualTo(AccountStatus.ACTIVE.ordinal());
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static MergePartiesRequest merge(UUID losingPartyId) {
        return MergePartiesRequest.builder()
                .losingPartyId(losingPartyId.toString())
                .justification("duplicate")
                .build();
    }

    private UUID createOrdinaryParty(UUID tenant, String legalName) {
        UUID partyId = asTenant(
                tenant,
                () -> UUID.fromString(partyService
                        .createCommercialAccount(CreateCommercialAccountRequest.builder()
                                .legalName(legalName)
                                .displayName(legalName)
                                .build())
                        .getPartyId()));
        createdParties.add(partyId);
        return partyId;
    }

    private List<Map<String, Object>> houseAccountRows(UUID tenant) {
        return owner.queryForList(
                "SELECT * FROM commercial_party WHERE tenant_id = ? AND house_account IS NOT NULL", tenant);
    }

    private UUID houseAccountId(UUID tenant) {
        return owner.queryForObject(
                "SELECT customer_id FROM commercial_party WHERE tenant_id = ? AND house_account = 'CASH_SALE'",
                UUID.class,
                tenant);
    }

    private long version(UUID partyId) {
        return owner.queryForObject("SELECT version FROM commercial_party WHERE customer_id = ?", Long.class, partyId);
    }

    private int status(UUID partyId) {
        return owner.queryForObject(
                "SELECT status FROM commercial_party WHERE customer_id = ?", Integer.class, partyId);
    }

    /** The {@code customer.party.updated} envelopes queued for one party, as the outbox stored them. */
    private List<JsonNode> partyFacts(UUID tenant, UUID partyId) {
        return owner
                .queryForList(
                        "SELECT payload FROM event_outbox WHERE tenant_id = ? AND record_key = ? ORDER BY created_at",
                        String.class,
                        tenant,
                        partyId.toString())
                .stream()
                .map(objectMapper::readTree)
                .filter(envelope -> "customer.party.updated"
                        .equals(envelope.path("eventType").stringValue()))
                .toList();
    }

    /** Waits until a backend is blocked inserting into commercial_party, i.e. on the unique index. */
    private void awaitABlockedInsert() {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            Integer blocked = owner.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                     WHERE wait_event_type = 'Lock' AND query ILIKE 'insert into commercial_party%'
                    """, Integer.class);
            if (blocked != null && blocked > 0) {
                return;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the competing insert", e);
            }
        }
        throw new IllegalStateException("The competing provisioning attempt never blocked on the unique index");
    }
}
