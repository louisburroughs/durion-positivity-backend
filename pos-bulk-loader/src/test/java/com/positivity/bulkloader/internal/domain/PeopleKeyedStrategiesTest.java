package com.positivity.bulkloader.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

/**
 * The three packs keyed by employee number, plus the two lookup helpers they share.
 *
 * <p>{@link PeopleResolutions} and {@link CatalogResolutions} are the only bridge between a file
 * that names a person by their HR employee number (or a part by its SKU) and the UUIDs the ingest
 * endpoints take. The interesting behaviour is all in what happens when a name resolves to nothing:
 * the row has to fail rather than load a record pointing at no one, and the lookup has to be asked
 * once per distinct key rather than once per row.
 */
@SuppressWarnings({"java:S100", "java:S1192"})
class PeopleKeyedStrategiesTest {

    private static final String PERSON_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b01";
    private static final String PRODUCT_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b02";

    /** Knows one employee ("E-1001") and one SKU ("BRK-100"); everything else resolves to nothing. */
    private static final class StubContext implements ResolutionContext {
        private final Map<String, Optional<?>> cache = new HashMap<>();
        final List<String> requestedUris = new ArrayList<>();

        @Override
        @NonNull
        public UUID jobLocationId() {
            return UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b00");
        }

        @Override
        @NonNull
        @SuppressWarnings("unchecked")
        public <R> Optional<R> get(@NonNull String serviceId, @NonNull String uri, @NonNull Class<R> responseType) {
            requestedUris.add(uri);
            if (uri.endsWith("/by-number/E-1001")) {
                return (Optional<R>) Optional.of(Map.of("personId", PERSON_ID));
            }
            if (uri.contains("/products/search") && uri.contains("sku=BRK-100")) {
                return (Optional<R>) Optional.of(Map.of("data", List.of(Map.of("productId", PRODUCT_ID))));
            }
            if (uri.contains("/products/search") && uri.contains("sku=EMPTY")) {
                return (Optional<R>) Optional.of(Map.of("data", List.of()));
            }
            if (uri.contains("/products/search") && uri.contains("sku=BLANK")) {
                return (Optional<R>) Optional.of(Map.of("data", List.of(Map.of("productId", "  "))));
            }
            return Optional.empty();
        }

        @Override
        @NonNull
        @SuppressWarnings("unchecked")
        public <R> Optional<R> memoize(@NonNull String cacheKey, @NonNull Supplier<Optional<R>> loader) {
            Optional<?> cached = cache.get(cacheKey);
            if (cached != null) {
                return (Optional<R>) cached;
            }
            Optional<R> loaded = loader.get();
            cache.put(cacheKey, loaded);
            return loaded;
        }
    }

    // ─── mechanic skills ─────────────────────────────────────────────────────

    private MechanicSkillLoaderRecord skill(MechanicSkillLoaderStrategy strategy, String employeeNumber, String level) {
        Map<String, String> row = new HashMap<>();
        row.put("employeeNumber", employeeNumber);
        row.put("skillCode", "BRAKES");
        row.put("proficiencyLevel", level);
        return strategy.mapRow(row);
    }

    @Test
    void mechanicSkills_resolveTheMechanicByEmployeeNumber() {
        MechanicSkillLoaderStrategy strategy = new MechanicSkillLoaderStrategy();
        StubContext context = new StubContext();

        MechanicSkillLoaderRecord resolved = strategy.resolve(skill(strategy, "E-1001", "3"), context);

        assertThat(resolved.getPersonId()).isEqualTo(PERSON_ID);
        assertThat(strategy.validate(resolved)).isEmpty();
        assertThat(strategy.getDomainType()).isEqualTo(DomainType.MECHANIC_SKILL);
    }

    @Test
    void mechanicSkills_anUnknownEmployeeNumberFailsTheRow() {
        // Loading the skill without a person would attach it to nobody, and the endpoint that
        // replaces a mechanic's skill set has no way to tell that apart from a deliberate clear.
        MechanicSkillLoaderStrategy strategy = new MechanicSkillLoaderStrategy();

        MechanicSkillLoaderRecord resolved = strategy.resolve(skill(strategy, "E-9999", "3"), new StubContext());

        assertThat(resolved.getPersonId()).isNull();
        assertThat(strategy.validate(resolved))
                .containsExactly("personId is required (or an employeeNumber that resolves to one)");
    }

    @Test
    void mechanicSkills_aPersonIdInTheFileIsNotLookedUpAgain() {
        MechanicSkillLoaderStrategy strategy = new MechanicSkillLoaderStrategy();
        StubContext context = new StubContext();
        MechanicSkillLoaderRecord record = skill(strategy, "E-1001", "3");
        record.setPersonId(PERSON_ID);

        assertThat(strategy.resolve(record, context).getPersonId()).isEqualTo(PERSON_ID);
        assertThat(context.requestedUris).isEmpty();
    }

    @Test
    void mechanicSkills_theSameEmployeeIsLookedUpOncePerJob() {
        MechanicSkillLoaderStrategy strategy = new MechanicSkillLoaderStrategy();
        StubContext context = new StubContext();

        strategy.resolve(skill(strategy, "E-1001", "3"), context);
        strategy.resolve(skill(strategy, "E-1001", "4"), context);

        assertThat(context.requestedUris).hasSize(1);
    }

    @Test
    void mechanicSkills_rejectAProficiencyOutsideOneToFive() {
        MechanicSkillLoaderStrategy strategy = new MechanicSkillLoaderStrategy();
        StubContext context = new StubContext();

        assertThat(strategy.validate(strategy.resolve(skill(strategy, "E-1001", "6"), context)))
                .containsExactly("proficiencyLevel must be between 1 and 5");
        assertThat(strategy.validate(strategy.resolve(skill(strategy, "E-1001", "0"), context)))
                .containsExactly("proficiencyLevel must be between 1 and 5");
    }

    @Test
    void mechanicSkills_rejectANonNumericOrMissingProficiency() {
        MechanicSkillLoaderStrategy strategy = new MechanicSkillLoaderStrategy();
        StubContext context = new StubContext();

        assertThat(strategy.validate(strategy.resolve(skill(strategy, "E-1001", "expert"), context)))
                .containsExactly("proficiencyLevel must be a whole number");
        assertThat(strategy.validate(strategy.resolve(skill(strategy, "E-1001", " "), context)))
                .containsExactly("proficiencyLevel is required");
    }

    @Test
    void mechanicSkills_requireASkillCode() {
        MechanicSkillLoaderStrategy strategy = new MechanicSkillLoaderStrategy();
        Map<String, String> row = new HashMap<>();
        row.put("employeeNumber", "E-1001");
        row.put("proficiencyLevel", "3");

        MechanicSkillLoaderRecord resolved = strategy.resolve(strategy.mapRow(row), new StubContext());

        assertThat(strategy.validate(resolved)).contains("skillCode is required");
    }

    // ─── user-to-person links ────────────────────────────────────────────────

    private UserPersonLinkLoaderRecord link(UserPersonLinkLoaderStrategy strategy, String user, String employee) {
        Map<String, String> row = new HashMap<>();
        row.put("username", user);
        row.put("employeeNumber", employee);
        return strategy.mapRow(row);
    }

    @Test
    void userPersonLinks_resolveThePersonAndLeaveTheUsernameAlone() {
        // pos-security-service owns usernames and resolves them when the batch lands, so the
        // loader must pass the name through untouched rather than guess at an id for it.
        UserPersonLinkLoaderStrategy strategy = new UserPersonLinkLoaderStrategy();

        UserPersonLinkLoaderRecord resolved = strategy.resolve(link(strategy, "jsmith", "E-1001"), new StubContext());

        assertThat(resolved.getPersonId()).isEqualTo(PERSON_ID);
        assertThat(resolved.getUsername()).isEqualTo("jsmith");
        assertThat(strategy.validate(resolved)).isEmpty();
        assertThat(strategy.getDomainType()).isEqualTo(DomainType.USER_PERSON_LINK);
    }

    @Test
    void userPersonLinks_anUnknownEmployeeNumberFailsTheRow() {
        UserPersonLinkLoaderStrategy strategy = new UserPersonLinkLoaderStrategy();

        UserPersonLinkLoaderRecord resolved = strategy.resolve(link(strategy, "jsmith", "E-9999"), new StubContext());

        assertThat(strategy.validate(resolved))
                .containsExactly("personId is required (or an employeeNumber that resolves to one)");
    }

    @Test
    void userPersonLinks_requireAUsername() {
        UserPersonLinkLoaderStrategy strategy = new UserPersonLinkLoaderStrategy();

        UserPersonLinkLoaderRecord resolved = strategy.resolve(link(strategy, " ", "E-1001"), new StubContext());

        assertThat(strategy.validate(resolved)).containsExactly("username is required");
    }

    @Test
    void userPersonLinks_aRowWithNoEmployeeNumberIsNotLookedUp() {
        UserPersonLinkLoaderStrategy strategy = new UserPersonLinkLoaderStrategy();
        StubContext context = new StubContext();

        strategy.resolve(link(strategy, "jsmith", null), context);

        assertThat(context.requestedUris).isEmpty();
    }

    // ─── security users ──────────────────────────────────────────────────────

    @Test
    void securityUsers_mapTheUsernameAndRoles_andCarryNoPassword() {
        // The pack deliberately has no password column; asserting the mapped fields is what keeps
        // one from being added back without a decision.
        SecurityUserLoaderStrategy strategy = new SecurityUserLoaderStrategy();

        SecurityUserLoaderRecord record =
                strategy.mapRow(Map.of("username", "jsmith", "roles", "TECHNICIAN;SHOP_MGR", "password", "hunter2"));

        assertThat(record.getUsername()).isEqualTo("jsmith");
        assertThat(record.getRoles()).isEqualTo("TECHNICIAN;SHOP_MGR");
        assertThat(strategy.validate(record)).isEmpty();
        assertThat(strategy.getDomainType()).isEqualTo(DomainType.SECURITY_USER);
    }

    @Test
    void securityUsers_requireAUsernameAndAtLeastOneRole() {
        SecurityUserLoaderStrategy strategy = new SecurityUserLoaderStrategy();

        assertThat(strategy.validate(strategy.mapRow(Map.of())))
                .containsExactly("username is required", "roles is required (semicolon-separated role names)");
    }

    // ─── catalog SKU lookups ─────────────────────────────────────────────────

    @Test
    void basePrices_resolveTheProductBySku() {
        BasePriceLoaderStrategy strategy = new BasePriceLoaderStrategy();
        Map<String, String> row = new HashMap<>();
        row.put("sku", " BRK-100 ");

        BasePriceRecord resolved = strategy.resolve(strategy.mapRow(row), new StubContext());

        assertThat(resolved.getProductId()).isEqualTo(PRODUCT_ID);
    }

    @Test
    void basePrices_aSkuTheCatalogDoesNotKnowLeavesTheProductUnset() {
        // Three ways the catalog says "no such SKU": nothing back, an empty match list, and a
        // match carrying a blank id. All three have to leave productId null so validate() fails
        // the row, rather than pricing whatever the loader happened to be holding.
        BasePriceLoaderStrategy strategy = new BasePriceLoaderStrategy();
        StubContext context = new StubContext();

        for (String sku : List.of("NOPE", "EMPTY", "BLANK")) {
            Map<String, String> row = new HashMap<>();
            row.put("sku", sku);
            assertThat(strategy.resolve(strategy.mapRow(row), context).getProductId())
                    .as("sku %s", sku)
                    .isNull();
        }
    }

    @Test
    void basePrices_theSameSkuIsLookedUpOncePerJob() {
        BasePriceLoaderStrategy strategy = new BasePriceLoaderStrategy();
        StubContext context = new StubContext();
        Map<String, String> row = new HashMap<>();
        row.put("sku", "BRK-100");

        strategy.resolve(strategy.mapRow(row), context);
        strategy.resolve(strategy.mapRow(row), context);

        assertThat(context.requestedUris).hasSize(1);
    }
}
