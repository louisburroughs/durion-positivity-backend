package com.positivity.mcp.internal.scopegraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.config.StaticRagPreloadProperties.StaticDocEntry;
import com.positivity.mcp.internal.scopegraph.ScopeSet.Seed;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #2385: the entities the 2026-10-01 tagging bake-off found with thin RAG coverage (location, employee, user, role,
 * campaign) each have a document whose subject they are, wired identically in both preload lists, and the en / fr-CA /
 * es messages that ask about them seed the intended entity from the shipped lexicon.
 */
class PeopleLocationCampaignCoverageTest {

    private static final Path MODULE_DIR = Paths.get(System.getProperty("user.dir"));
    private static final Path RAG_DIR = MODULE_DIR.resolve("src/main/resources/rag");

    /** The documents #2385 adds: id, file, scope, required-permissions, entities. */
    private record Expected(String id, String file, String scope, List<String> permissions, List<String> entities) {}

    private static final List<Expected> ADDED = List.of(
            new Expected(
                    "shop.locations",
                    "locations-guide.md",
                    "shopmanager",
                    List.of("location:bay:read", "shop:schedule:view", "shop:technician:view"),
                    List.of("location")),
            new Expected(
                    "people.employees",
                    "employees-guide.md",
                    "hr",
                    List.of("people:employee:view"),
                    List.of("employee")),
            new Expected(
                    "admin.users-roles",
                    "users-and-roles-guide.md",
                    "admin",
                    List.of("security:user:view", "security:role:view"),
                    List.of("user", "role")),
            new Expected(
                    "marketing.campaigns",
                    "marketing-campaigns-guide.md",
                    "marketing",
                    List.of("marketing:campaign:view", "marketing:template:view", "marketing:stats:view"),
                    List.of("campaign")));

    /** Backticked strings in the new documents that look like a permission code but are not one. */
    private static final Set<String> NOT_PERMISSIONS =
            Set.of("domain:resource:action", "kind:value", "service:alignment");

    private static final Pattern BACKTICKED_CODE = Pattern.compile("`([a-z][a-z-]*:[a-zA-Z_-]+(?::[a-zA-Z_-]+)*)`");
    private static final Pattern PERMISSION_NAME = Pattern.compile("-\\s*name:\\s*\"([^\"]+)\"");

    private static final TermMatcher LEXICON_MATCHER = TermMatcher.of(EntityLexiconLoader.loadDefault());

    private static Set<String> seededEntities(String message) {
        return LEXICON_MATCHER.match(message).stream().map(Seed::entity).collect(Collectors.toSet());
    }

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"default", "alpha"})
    @DisplayName("each added document is preloaded with its scope, permissions and entities, and its file exists")
    void addedDocumentsArePreloaded(String profile) {
        Map<String, StaticDocEntry> byId = ScopeGraphRealConfigValidationTest.ragDocs(profile).stream()
                .collect(Collectors.toMap(StaticDocEntry::id, doc -> doc));

        for (Expected expected : ADDED) {
            StaticDocEntry doc = byId.get(expected.id());
            assertThat(doc).as("%s preload entry %s", profile, expected.id()).isNotNull();
            assertThat(doc.sourcePath()).isEqualTo("classpath:rag/" + expected.file());
            assertThat(doc.ragScope()).as("rag-scope of %s", expected.id()).isEqualTo(expected.scope());
            assertThat(doc.requiredPermissions())
                    .as("required-permissions of %s", expected.id())
                    .containsExactlyElementsOf(expected.permissions());
            assertThat(doc.entities())
                    .as("entities of %s", expected.id())
                    .containsExactlyElementsOf(expected.entities());
            assertThat(RAG_DIR.resolve(expected.file())).as("document file").isRegularFile();
        }
    }

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"default", "alpha"})
    @DisplayName("location, employee, user, role and campaign are each the subject of a dedicated document")
    void thinEntitiesHaveADedicatedDocument(String profile) {
        List<StaticDocEntry> docs = ScopeGraphRealConfigValidationTest.ragDocs(profile);
        Map<String, String> dedicated = Map.of(
                "location", "shop.locations",
                "employee", "people.employees",
                "user", "admin.users-roles",
                "role", "admin.users-roles",
                "campaign", "marketing.campaigns");

        dedicated.forEach((entity, docId) -> {
            Set<String> covering = docs.stream()
                    .filter(doc -> doc.entities().contains(entity))
                    .map(StaticDocEntry::id)
                    .collect(Collectors.toCollection(TreeSet::new));
            assertThat(covering).as("%s: documents listing %s", profile, entity).contains(docId);
        });
        // Before #2385 location, employee and user each had one annotated document and campaign none.
        for (String entity : List.of("location", "employee", "user")) {
            assertThat(docs.stream().filter(doc -> doc.entities().contains(entity)))
                    .as("%s: documents listing %s", profile, entity)
                    .hasSizeGreaterThanOrEqualTo(2);
        }
    }

    @Test
    @DisplayName("the marketing rag-scope has its domains: sentence and needs no domain_scopes line")
    void marketingScopeHasADomainSentence() {
        EntityLexicon lexicon = EntityLexiconLoader.loadDefault();

        assertThat(lexicon.domains()).containsKey("marketing");
        assertThat(lexicon.domains().get("marketing")).contains("campaign");
        // Tool domain and rag-scope are spelled alike, so the graph maps marketing -> marketing by default.
        assertThat(lexicon.domainScopes()).doesNotContainKey("marketing");
        assertThat(lexicon.entities())
                .filteredOn(entity -> entity.key().equals("campaign"))
                .singleElement()
                .satisfies(entity -> assertThat(entity.domain()).isEqualTo("marketing"));
    }

    @ParameterizedTest(name = "[{0}] {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "location | What are the hours at the Elm Street store?",
                "location | Quelles sont les heures d'ouverture du magasin de la rue Elm ?",
                "location | ¿Cuál es el horario de la tienda de Elm Street?",
                "location | Which branch am I working at today?",
                "location | Quelle succursale est ouverte dimanche ?",
                "location | ¿Qué sucursal abre el domingo?",
                "employee | Which technicians work at location X?",
                "employee | Quels techniciens travaillent à l'emplacement X ?",
                "employee | ¿Qué técnicos trabajan en la ubicación X?",
                "employee | Add a new employee for the north shop",
                "employee | Ajouter un nouvel employé",
                "employee | Agregar un nuevo empleado",
                "user | Create a user account for Ana",
                "user | Créer un compte utilisateur pour Ana",
                "user | Crear una cuenta de usuario para Ana",
                "role | Give Ana the service advisor role",
                "role | Attribuer le rôle de conseiller de service à Ana",
                "role | Asignar el rol de asesor de servicio a Ana",
                "campaign | Which campaigns are active?",
                "campaign | Launch the spring alignment campaign",
                "campaign | Lancer une campagne",
                "campaign | Quelles campagnes sont actives ?",
                "campaign | ¿Qué campañas están activas?",
                "campaign | Lanzar una campaña de marketing",
            })
    @DisplayName("en / fr-CA / es messages seed the intended entity from the shipped lexicon")
    void messagesSeedTheIntendedEntity(String entity, String message) {
        assertThat(seededEntities(message))
                .as("entities seeded by '%s'", message)
                .contains(entity);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "What is the balance on invoice 1042?",
                "Bonjour, comment ça va ?",
                "¿Cuál es el precio de este neumático?",
                "Show me the open workorders",
            })
    @DisplayName("unrelated messages seed none of the five entities")
    void unrelatedMessagesDoNotSeedThem(String message) {
        assertThat(seededEntities(message))
                .as("entities seeded by '%s'", message)
                .doesNotContainAnyElementsOf(List.of("location", "employee", "user", "role", "campaign"));
    }

    @Test
    @DisplayName("every permission code the added documents name is registered by a module")
    void documentPermissionCodesAreRegistered() throws IOException {
        Set<String> registered = registeredPermissionCodes();
        assertThat(registered).contains("marketing:campaign:send", "people:jobRole:view", "location:bay:read");

        for (Expected expected : ADDED) {
            String text = Files.readString(RAG_DIR.resolve(expected.file()));
            Set<String> named = new TreeSet<>();
            Matcher matcher = BACKTICKED_CODE.matcher(text);
            while (matcher.find()) {
                named.add(matcher.group(1));
            }
            named.removeAll(NOT_PERMISSIONS);
            assertThat(named).as("%s names permission codes", expected.file()).isNotEmpty();
            assertThat(named)
                    .as("%s: every permission code must exist in a module's permissions.yaml", expected.file())
                    .isSubsetOf(registered);
        }
    }

    /** Every {@code name:} of every sibling module's {@code src/main/resources/permissions.yaml}. */
    private static Set<String> registeredPermissionCodes() throws IOException {
        Set<String> codes = new TreeSet<>();
        try (Stream<Path> modules = Files.list(MODULE_DIR.getParent())) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path permissions = module.resolve("src/main/resources/permissions.yaml");
                if (Files.isRegularFile(permissions)) {
                    Matcher matcher = PERMISSION_NAME.matcher(Files.readString(permissions));
                    while (matcher.find()) {
                        codes.add(matcher.group(1));
                    }
                }
            }
        }
        return codes;
    }
}
